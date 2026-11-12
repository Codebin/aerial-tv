package app.orchard.tv.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import app.orchard.tv.BuildConfig
import app.orchard.tv.apps.AppInventory
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.update.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen time for one TV: measure what runs in front, hand the numbers to the household's
 * tv-manager, keep the rules it hands back, and answer the one question that matters on the TV —
 * may this package run right now.
 *
 * The TV decides. Rules are cached locally and [check] reads only the cache, so a NAS that is
 * rebooting, switched off, or unreachable on Wi-Fi must never turn the TV into "allow everything".
 * The backend is where the parent edits the rules and reads history; losing the link delays
 * reporting, it does not lift a limit.
 *
 * Measuring uses the accessibility window events the guard already watches (see
 * [app.orchard.tv.guard.HomeGuardService]); GET_USAGE_STATS is not required, which is what makes
 * this work on the firmwares where that permission cannot be granted at all.
 */
object ScreenTime {
    private const val TAG = "AerialScreenTime"
    private const val PREFS = "screen_time"
    private const val TICK_MS = 30_000L

    /**
     * The last minute of a countdown is judged every second (owner, 2026-11-05): a stop that waits
     * for the next 30 s tick lets the viewer overrun the rule by that much — and the phone page,
     * which counts every settled minute, then reads "over by X" for time that was never allowed.
     * The heartbeat does not speed up; only the local decision does. Once a fresh exception or a
     * lifted rule puts the boundary further away than this window again, the loop falls back to
     * TICK_MS on its own.
     */
    private const val FAST_TICK_MS = 1_000L
    private const val FAST_WINDOW_MS = 60_000L

    private const val CONNECT_MS = 4_000
    private const val READ_MS = 8_000
    private const val MAX_PENDING = 400

    /**
     * The fallback sweep for an app list nobody asked about. Installs and uninstalls are heard
     * through [packageWatcher] and a parent can ask from the page, so this only catches what those
     * miss. It used to be half an hour; on this TV (a 2018 Bravia whose system_server already
     * spends its day near 70%) an untouched list is not worth a scan that often.
     */
    private const val APPS_CHECK_MS = 6 * 3600_000L

    /** An unchanged app list is sent again this often, so a backend whose data was reset recovers. */
    private const val APPS_TTL_MS = 6 * 3600_000L

    /** Base URL baked in at build time from local/tv-manager.properties; empty means no backend. */
    val baseUrl: String get() = BuildConfig.TV_MANAGER_URL
    val enabled: Boolean get() = baseUrl.isNotEmpty()

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var deviceId: String = ""
    @Volatile private var secret: String = ""
    @Volatile private var rules: JSONObject? = null
    @Volatile private var rulesVersion: Long = 0
    @Volatile private var online = false

    /**
     * The backend's usage-file generation (POLICY-DESIGN §9). It moves only when a parent presses
     * "reset today": the backend zeroes its day while this TV's local [todayMs] would keep
     * counting, so every policy reply carries the epoch and a newer one restarts the local day.
     * Not a midnight key — the daily roll stays on [dayKey]. An old backend never sends one; 0
     * equals a fresh TV's 0 and changes nothing, ever.
     */
    @Volatile private var usageEpoch = 0L

    /** The epoch the last policy reply carried, whether or not it was newer (for stime_dump). */
    @Volatile private var lastServedEpoch = 0L

    /** The version code the backend was last told about, so an idle TV still reports it once. */
    @Volatile private var lastReportedCode = 0L

    /**
     * Pulls the parent PIN hash out of a policy reply and drops it from the object that is about to
     * be cached: the rules a child could read off the device should not carry the hash of the PIN
     * that locks the settings. Callers hand the result to AerialSettings.adoptPinHash on the main
     * thread; an empty string just means that reply carried none.
     */
    private fun stripPin(policy: JSONObject?): String {
        val hex = policy?.optJSONObject("pin")?.optString("hash").orEmpty()
        policy?.remove("pin")
        return hex
    }

    /**
     * Adopts the backend's PIN on the main thread, after making sure the settings object can take
     * it. Two guards, both for the case where this process was started by the guard rather than by
     * Aerial's UI: `adoptPinHash` writes SharedPreferences through a `lateinit` context that only
     * [AerialSettings.init] sets, so reaching it first killed the whole process (verified on Sony
     * BRAVIA 4K UR3, Android 10: 1.78 (102) restarted every ~20 s, screen time never registering),
     * and a heartbeat that can crash must not be the thing that crashes a TV. Losing one adoption
     * is cheap — every policy reply carries the hash again — so this is best-effort by design.
     */
    private fun adoptBackendPin(hex: String) {
        runCatching {
            AerialSettings.ensureReady(app)
            if (AerialSettings.adoptPinHash(hex)) Log.i(TAG, "parental PIN adopted from the backend")
        }.onFailure { Log.w(TAG, "pin adoption skipped: ${it.message}") }
    }

    /** The `usageEpoch` one policy reply carries; an old backend's missing field means 0. */
    internal fun servedEpoch(policy: JSONObject?): Long = policy?.optLong("usageEpoch", 0) ?: 0

    /** Only a strictly newer epoch restarts the local day — the same one on every poll changes nothing. */
    internal fun epochResetsUsage(served: Long, cached: Long): Boolean = served > cached

    /**
     * Folds one policy reply's usage epoch into the local state. Runs on the main thread, which is
     * where the tallies it may drop live. Returns true when this reply was newer and the local
     * "today" restarted to match the backend's reset.
     */
    private fun adoptUsageEpoch(epoch: Long): Boolean {
        lastServedEpoch = epoch
        if (!epochResetsUsage(epoch, usageEpoch)) return false
        usageEpoch = epoch
        todayMs.clear()
        synchronized(pending) { pending.clear() }
        // Whatever is playing counts from this instant: the stretch before the parent pressed
        // "reset today" is gone from the backend's day and must not be charged to the new one.
        if (frontPkg != null) frontSince = SystemClock.elapsedRealtime()
        persist()
        Log.i(TAG, "the backend reset today's usage (epoch $epoch); local tallies dropped")
        return true
    }

    /** The app list the backend last took ([AppInventory.stamp]) and when, on the wall clock. */
    @Volatile private var appsStamp: String = ""
    @Volatile private var appsAt: Long = 0L

    /** When the package manager was last asked, on the elapsed clock: the scan runs on its own pace. */
    private var appsCheckedAt = 0L

    /** The last package broadcast answered, so one update's several broadcasts send one report. */
    private var lastPackageEvent = 0L

    /**
     * The TV's app list changed. The cheapest trigger there is: nothing runs until something is
     * installed, uninstalled or updated, and the report follows at once instead of waiting for the
     * sweep. Kept for the process's life; the guard keeps it alive.
     */
    private val packageWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPackageEvent < 5_000L) return      // an update fires several of these
            lastPackageEvent = now
            appsCheckedAt = 0L
            scope.launch { pushApps(force = true) }
        }
    }

    /**
     * Called on the main thread when what is in front has just run out of time: Aerial brings
     * itself back and shows the gate. Null until MainActivity sets it.
     */
    @Volatile var onBlocked: ((Block) -> Unit)? = null

    /**
     * The block the viewer has not been shown the gate for yet. Pulling the TV home and showing
     * the card cannot ride the same intent: a background activity start from this process is
     * dropped in silence on Android 10 (Sony KD-65X9500H, 2026-11-07 — the countdown ended, the
     * verdict said blocked and the event log has it, yet the app kept playing until the viewer
     * pressed Home), while the guard's system Home action always comes back and carries no
     * extras. So the pull-back asks the system for Home (see [watchForLimit]) and the block waits
     * here until Aerial is in front again and takes it ([takePendingBlock]).
     */
    @Volatile private var pendingBlock: Block? = null
    private var pendingBlockAt = 0L

    /** How long a parked block may still be shown: an old overrun must not pop up hours later. */
    private const val PENDING_BLOCK_MS = 15_000L

    /**
     * Takes the block parked for display. Null when there is none, or when it has been waiting
     * longer than [maxAgeMs]: by then the viewer is elsewhere and the card would be about
     * something they already left behind.
     */
    fun takePendingBlock(maxAgeMs: Long = PENDING_BLOCK_MS): Block? {
        val b = pendingBlock ?: return null
        pendingBlock = null
        if (SystemClock.elapsedRealtime() - pendingBlockAt > maxAgeMs) return null
        return b
    }

    /**
     * Called on the main thread when what is in front starts or stops being governed by a rule:
     * null means nothing to show. The guard's screen-time pill (ScreenTimeOverlay) rides on this.
     */
    @Volatile var onStatus: ((Status?) -> Unit)? = null

    /** The last status handed out, so thirty seconds of the same minute announce nothing. */
    private var announced: String? = "?"

    /** What the tick loop already announced, so one overrun is not repeated every 30 seconds. */
    private var limitNotified: String? = null

    // Session bookkeeping. Accessibility events arrive on the main thread, as does the tick,
    // so these need no locking — keep every touch of them on Dispatchers.Main.
    private var frontPkg: String? = null
    private var frontSince = 0L                       // elapsedRealtime, immune to clock changes
    private var dayKey = dayKeyOf(System.currentTimeMillis())
    private val todayMs = HashMap<String, Long>()     // settled, this local day
    private val pending = ArrayList<JSONObject>()     // closed sessions waiting for delivery

    /**
     * True while the panel is dark. A TV in standby keeps its last app on the window stack and
     * fires no further window event, so without this the dark hours are measured as watching:
     * one settings screen left up overnight racked up 14.5 h on the Sony KD-65X9500H (2026-11-12)
     * and spent the whole-TV allowance before lunch. Main-thread bookkeeping, like the fields
     * above; internal so the standby tests can read it.
     */
    internal var screenOff = false

    /**
     * Why the TV refuses a package. Shown by the dialog the viewer gets instead of the app.
     * [DayLock] is the parent's whole-day lock (POLICY-DESIGN §10): the press disables the whole
     * day and the lock lifts on its own at the midnight stamped into `dayLockUntil`. [Fallback]
     * is the backend's last resort (FALLBACK-DESIGN §4): the tv-manager saw a strategy pass its
     * expected end without this TV ever reporting it ended, and popped the lock over adb.
     */
    enum class Reason { Blocked, Window, GroupLimit, TvLimit, DayLock, Fallback }

    data class Block(val pkg: String, val groupId: String?, val reason: Reason, val leftMs: Long = 0)

    /**
     * What governs [pkg] right now: the rule that answered and how much time it leaves. [Passed]
     * is a running exception — the app may play, and [leftMs] says for how much longer. [DayLock]
     * is the parent's whole-day lock (POLICY-DESIGN §10): it stops every grouped app until its
     * midnight, outranking every other rule including an exception, and [leftMs] counts down to
     * that expiry.
     */
    enum class Kind { Blocked, Window, GroupLimit, TvLimit, Passed, DayLock }

    data class Status(
        val pkg: String,
        val kind: Kind,
        val groupId: String?,
        /** The parent's name for the group, already picked for this TV's language. */
        val groupName: String?,
        /**
         * Time left for this rule, in ms: until a quota runs out, until the current time window
         * closes, or — when [blockedNow] — until the next time window opens. 0 (or less) once a
         * quota is reached.
         */
        val leftMs: Long,
        /**
         * True when this rule stops the app right now despite carrying a countdown: a time policy
         * outside its allowed windows counts up to the next window opening, not down to a stop.
         * Quota kinds block through [leftMs] running out instead, so they never set this.
         */
        val blockedNow: Boolean = false,
    ) {
        /** True when this rule stops the app now. A running exception never does. */
        val blocks: Boolean get() = blockedNow || kind == Kind.Blocked ||
            ((kind == Kind.GroupLimit || kind == Kind.TvLimit) && leftMs <= 0)
    }

    fun init(context: Context) {
        if (!enabled) return
        synchronized(this) {
            if (::app.isInitialized) return
            app = context.applicationContext
            val p = prefs()
            deviceId = p.getString("device_id", "")?.takeIf { it.isNotEmpty() } ?: newDeviceId(p)
            secret = p.getString("secret", "").orEmpty()
            restore(p)
            Log.i(TAG, "screen time on: $baseUrl as $deviceId, ${if (secret.isEmpty()) "not enrolled" else "enrolled"}")
            watchPackages(app)
            watchScreen(app)
            scope.launch { tickLoop() }
        }
    }

    /**
     * Listens for the package changes that would make the reported app list stale. A TV that never
     * installs anything costs one registered receiver and nothing else.
     */
    private fun watchPackages(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching {
            // System broadcasts come from the system, so the receiver has to stay exported on 33+.
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(packageWatcher, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(packageWatcher, filter)
            }
        }.onFailure { Log.w(TAG, "cannot watch package changes; the sweep still covers it", it) }
    }

    /**
     * Starts and stops the clock with the panel. The receiver runs on the main thread, which is
     * where the session fields above live. Some firmwares cut Aerial off before the screen
     * broadcasts land (Xiaomi MFTR0 force-stops it 1.3 s after the panel goes dark, 2026-11-08),
     * so the tick loop also polls the same answer as a safety net.
     */
    private fun watchScreen(context: Context) {
        screenOff = !isInteractive(context)           // a process woken in standby starts paused
        ContextCompat.registerReceiver(
            context, screenWatcher,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private val screenWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> noteScreenOff()
            Intent.ACTION_SCREEN_ON -> noteScreenOn()
            else -> Unit
        }
    }

    /** Whether the panel is awake. A TV that cannot say is taken as awake — the old behaviour. */
    private fun isInteractive(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    }.getOrDefault(true)

    /**
     * The panel went dark: settle what was really watched up to this instant, then hold the clock.
     * The running app stays [frontPkg] — the firmware usually puts that same screen back when the
     * TV wakes, and waking restarts its stretch from the wake-up anyway.
     */
    internal fun noteScreenOff(now: Long = SystemClock.elapsedRealtime()) {
        if (screenOff) return
        closeFront(now)
        screenOff = true
        persist()
    }

    /** The panel woke up: the dark stretch belonged to no viewer, so none of it is counted. */
    internal fun noteScreenOn(now: Long = SystemClock.elapsedRealtime()) {
        if (!screenOff) return
        screenOff = false
        if (frontPkg != null) frontSince = now
        persist()
    }

    /**
     * A window became the front one. Called from the guard's accessibility callback and from its
     * usage-stats fallback, so the two sources must agree on one stream of sessions.
     */
    fun onForeground(pkg: String?) {
        if (!enabled || !::app.isInitialized) return
        if (pkg == frontPkg) return
        val now = SystemClock.elapsedRealtime()
        closeFront(now)
        // Coming home stops the clock. Aerial is the launcher, not an app someone sat down to
        // watch: counting it would spend the whole daily allowance on the idle home screen.
        frontPkg = pkg?.takeIf { it != app.packageName }
        frontSince = now
        persist()
        publishStatus()
        // An app that already spent its time gets pulled back the moment it comes up — through the
        // system launcher, recents or voice, not only Aerial's own tiles, whose check in
        // MainActivity.launch this path cannot rely on.
        watchForLimit()
    }

    /**
     * Publishes the rule in force again, even if the last one announced was the same. The guard
     * calls this when it rebinds, so a pill that was up before the process restarted comes back.
     */
    fun publishNow() {
        announced = null
        publishStatus()
    }

    /**
     * Hands the rule in force to [onStatus], and nothing when it is the same one at the same
     * minute: the tick loop calls it every tick, and the pill's seconds run on the pill's own
     * clock — a per-second re-announcement of the same minute would only redraw the same text.
     */
    private fun publishStatus() {
        val pkg = frontPkg
        val s = if (pkg == null) null else status(pkg)
        val key = s?.let { "${it.kind}|${it.leftMs / 60_000}" }
        if (key == announced) return
        announced = key
        onStatus?.invoke(s)
    }

    /** Settles the running session without ending it, so a long film shows up while it plays. */
    private fun closeFront(now: Long) {
        // A dark panel is nobody watching: the stretch before the switch-off was settled when the
        // screen went out, and anything that moved while it was dark is not company.
        if (screenOff) return
        val pkg = frontPkg ?: return
        val ms = now - frontSince
        if (ms < 1000) return
        addToday(pkg, ms)
        pending.add(
            JSONObject()
                .put("pkg", pkg)
                .put("start", wallStart(now, frontSince))
                .put("end", wallStart(now, now))
        )
        while (pending.size > MAX_PENDING) pending.removeAt(0)
        frontSince = now
    }

    private fun wallStart(now: Long, since: Long): Long =
        System.currentTimeMillis() - (now - since)

    private fun addToday(pkg: String, ms: Long) {
        rollDayIfNeeded()
        todayMs[pkg] = (todayMs[pkg] ?: 0L) + ms
    }

    private fun rollDayIfNeeded() {
        val k = dayKeyOf(System.currentTimeMillis())
        if (k != dayKey) {
            // The history lives on the backend; the local tally is only what today's limits need.
            dayKey = k
            todayMs.clear()
        }
    }

    /** Milliseconds this package's group has used today, including what is running right now. */
    private fun usedFor(members: List<String>): Long {
        rollDayIfNeeded()
        var total = 0L
        for (m in members) total += todayMs[m] ?: 0L
        val front = frontPkg
        if (front != null && front in members) total += SystemClock.elapsedRealtime() - frontSince
        return total
    }

    private fun usedTotal(): Long {
        rollDayIfNeeded()
        var total = 0L
        for (v in todayMs.values) total += v
        val front = frontPkg
        if (front != null) total += SystemClock.elapsedRealtime() - frontSince
        return total
    }

    // ------------------------------------------------------------------ decisions from the cache

    /**
     * Whether [pkg] may be in front. Priority order, first match wins, identical to the backend's
     * /api/tv/verdict so the two can never disagree (POLICY-DESIGN.md §2, as revised by §10): day
     * lock, exception, blocked group, time policies, group-bound quota policies, whole-TV quota
     * policies. Apps in no group are counted but never stopped — the day lock included.
     *
     * With no rules cached yet (a TV that never reached the backend) everything is allowed — a
     * half-provisioned setup must not brick the TV.
     */
    fun check(pkg: String): Block? {
        val s = status(pkg) ?: return null
        if (!s.blocks) return null
        val reason = when (s.kind) {
            Kind.DayLock -> Reason.DayLock
            Kind.Blocked -> Reason.Blocked
            Kind.Window -> Reason.Window
            Kind.GroupLimit -> Reason.GroupLimit
            Kind.TvLimit -> Reason.TvLimit
            Kind.Passed -> return null
        }
        return Block(pkg, s.groupId, reason, s.leftMs)
    }

    /** A moment to judge the rules against, so the same code can be tested without waiting for one. */
    internal data class Moment(val hour: Int, val minute: Int, val dayOfWeek: Int, val nowMs: Long) {
        val minutes: Int get() = hour * 60 + minute
        val weekend: Boolean get() = dayOfWeek >= 6
    }

    /**
     * What governs [pkg] at [at], for the pill the viewer sees over the app. Null when nothing
     * does: an app in no group is only measured, and a TV with no rules cached allows everything
     * (see [check]).
     *
     * Same order as the backend's verdict (POLICY-DESIGN.md §2, as revised by §10), first match
     * wins — day lock first, then exception, blocked group, time policies, and the quota policies
     * — but a rule that still has room is reported too; that is the countdown the family asked to
     * see while the app is playing. When several rules all have room, the tightest one is the one
     * shown.
     */
    internal fun statusOf(
        rules: JSONObject?,
        pkg: String,
        at: Moment,
        usedGroup: (List<String>) -> Long,
        usedTotal: () -> Long,
    ): Status? {
        val r = rules ?: return null
        val group = groupOf(r, pkg) ?: return null
        val gid = group.optString("id")
        val name = groupName(group)
        // Day lock (POLICY-DESIGN §10), judged before anything else: the parent disabled today, so
        // no exception may revive a grouped app — a lock is not a window that happens to be shut.
        // The until is an absolute epoch ms (the NAS's next midnight), compared against [at] here
        // and against the wall clock in [status], so the lock lifts by itself at midnight and an
        // absent or 0 field (an old backend, or never locked) never matches. Ungrouped apps were
        // returned null above: the lock covers grouped apps only, as in the backend's verdict.
        val lockUntil = r.optLong("dayLockUntil", 0L)
        if (lockUntil > at.nowMs)
            return Status(pkg, Kind.DayLock, gid, name, lockUntil - at.nowMs, blockedNow = true)
        exceptionLeft(r, gid, at.nowMs)?.let { return Status(pkg, Kind.Passed, gid, name, it) }
        if (group.optBoolean("blocked")) return Status(pkg, Kind.Blocked, gid, name, 0)

        // Every quota/time policy touching this group, each with its own countdown (the same walk
        // FALLBACK-DESIGN's heartbeat reads, so the two can never disagree about what governs).
        val beats = walkPolicies(r, gid, membersOf(group), at, usedGroup, usedTotal)
        // A time policy whose windows do not cover "now" stops the app outright and counts up to
        // the next opening; first match wins, as the verdict does.
        beats.firstOrNull { it.waiting }?.let {
            return Status(pkg, Kind.Window, gid, name, it.leftMs, blockedNow = true)
        }
        var windowLeft: Long? = null
        var groupLeft: Long? = null
        var tvLeft: Long? = null
        for (b in beats) when (b.kind) {
            Kind.Window -> if (windowLeft == null || b.leftMs < windowLeft) windowLeft = b.leftMs
            Kind.GroupLimit -> if (groupLeft == null || b.leftMs < groupLeft) groupLeft = b.leftMs
            Kind.TvLimit -> if (tvLeft == null || b.leftMs < tvLeft) tvLeft = b.leftMs
            else -> Unit
        }

        return when {
            groupLeft != null && groupLeft <= 0 -> Status(pkg, Kind.GroupLimit, gid, name, groupLeft)
            tvLeft != null && tvLeft <= 0 -> Status(pkg, Kind.TvLimit, gid, name, tvLeft)
            else -> {
                val tightest = listOfNotNull(
                    groupLeft?.let { Kind.GroupLimit to it },
                    tvLeft?.let { Kind.TvLimit to it },
                    windowLeft?.let { Kind.Window to it },
                ).minByOrNull { it.second }
                tightest?.let { Status(pkg, it.first, gid, name, it.second) }
            }
        }
    }

    // ------------------------------------------------- per-policy counts (FALLBACK-DESIGN §1 / §2)

    /**
     * One policy's own countdown over a package, judged at one moment. [statusOf] folds these
     * into the single answer the pill shows; the fallback heartbeat and the strategy_end events
     * need every one of them, because the backend guards each strategy separately
     * (FALLBACK-DESIGN §1: "为当时该前台包所有活动策略各发一条"). A used-up quota carries a
     * negative [leftMs] on purpose: the expected end is then a passing instant the backend's
     * guard will cross in any minute, which is exactly a TV that should have stopped and did not.
     */
    internal data class Beat(
        val policyId: String,
        val kind: Kind,
        val leftMs: Long,
        /**
         * A time policy whose windows do not cover the moment: [leftMs] counts **up** to the next
         * opening, it is not a countdown to a stop. Such a beat explains a block (and belongs to
         * strategy_end, §2), but never rides the usage heartbeat — §1's expectedEndMs means the
         * instant the current window closes, and there is no open window to close.
         */
        val waiting: Boolean,
    )

    /** The enabled quota/time policies touching [gid] at [at], each as its own [Beat]. */
    private fun walkPolicies(
        rules: JSONObject,
        gid: String,
        members: List<String>,
        at: Moment,
        usedGroup: (List<String>) -> Long,
        usedTotal: () -> Long,
    ): List<Beat> {
        val out = ArrayList<Beat>()
        for (p in enabledPolicies(rules, "time")) {
            if (!policyApplies(p, gid)) continue
            val state = windowState(p.optJSONObject("config"), at) ?: continue
            out.add(Beat(p.optString("id"), Kind.Window, state.leftMs, waiting = !state.open))
        }
        for (p in enabledPolicies(rules, "quota")) {
            if (!policyApplies(p, gid)) continue
            val limit = quotaLimitMs(p, at) ?: continue
            val wholeTv = quotaIsWholeTv(p)
            val left = if (wholeTv) limit - usedTotal() else limit - usedGroup(members)
            out.add(Beat(p.optString("id"), if (wholeTv) Kind.TvLimit else Kind.GroupLimit, left, waiting = false))
        }
        return out
    }

    /**
     * Every quota/time policy governing [pkg] right now, each with its own countdown. Null when
     * none does: an app in no group, or one taken over by a day lock, an exception or a blocked
     * group — none of which is a strategy counting down to zero, so none of which belongs in the
     * fallback's records (§1: "DayLock、例外（Passed）不是「等待归零」的倒计时，不进 activeStrategies").
     */
    internal fun governingOf(
        rules: JSONObject?,
        pkg: String,
        at: Moment,
        usedGroup: (List<String>) -> Long,
        usedTotal: () -> Long,
    ): Pair<String, List<Beat>>? {
        val r = rules ?: return null
        val group = groupOf(r, pkg) ?: return null
        val gid = group.optString("id")
        if (r.optLong("dayLockUntil", 0L) > at.nowMs) return null
        if (exceptionLeft(r, gid, at.nowMs) != null) return null
        if (group.optBoolean("blocked")) return null
        val beats = walkPolicies(r, gid, membersOf(group), at, usedGroup, usedTotal)
        return if (beats.isEmpty()) null else gid to beats
    }

    /**
     * One entry of the usage heartbeat's `activeStrategies` array (FALLBACK-DESIGN §1): this TV's
     * wall-clock `expectedEndMs` is the instant the policy is expected to reach zero — a quota
     * recomputed every beat as now + leftMs (the count slides with the viewing), a time policy as
     * the absolute instant its open window closes. The backend's 90 s grace absorbs clock skew.
     */
    internal data class ActiveStrategy(
        val id: String,
        /** Wire names, §1: "quota" | "time". */
        val kind: String,
        val gid: String,
        val pkg: String,
        val expectedEndMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("kind", kind).put("gid", gid).put("pkg", pkg)
            .put("expectedEndMs", expectedEndMs)
    }

    /** The heartbeat's list for [pkg]: the beats of [governingOf] that count **down** to a stop. */
    internal fun activeStrategiesOf(
        rules: JSONObject?,
        pkg: String,
        at: Moment,
        usedGroup: (List<String>) -> Long,
        usedTotal: () -> Long,
    ): List<ActiveStrategy> {
        val (gid, beats) = governingOf(rules, pkg, at, usedGroup, usedTotal) ?: return emptyList()
        return beats.filterNot { it.waiting }.map {
            ActiveStrategy(
                it.policyId,
                if (it.kind == Kind.Window) "time" else "quota",
                gid, pkg, at.nowMs + it.leftMs,
            )
        }
    }

    /** The heartbeat's list for the app in front, judged now. Runs on the main thread. */
    private fun activeStrategiesNow(pkg: String): List<ActiveStrategy> {
        val now = CalendarBits.now()
        return activeStrategiesOf(
            rules, pkg,
            Moment(now.hour, now.minute, now.dayOfWeek, System.currentTimeMillis()),
            usedGroup = { members -> usedFor(members) },
            usedTotal = { usedTotal() },
        )
    }

    fun status(pkg: String): Status? {
        val now = CalendarBits.now()
        return statusOf(
            rules, pkg,
            Moment(now.hour, now.minute, now.dayOfWeek, System.currentTimeMillis()),
            usedGroup = { members -> usedFor(members) },
            usedTotal = { usedTotal() },
        )
    }

    private fun groupOf(r: JSONObject, pkg: String): JSONObject? {
        val gs = r.optJSONArray("groups") ?: return null
        for (i in 0 until gs.length()) {
            val g = gs.getJSONObject(i)
            val m = g.optJSONArray("members") ?: continue
            for (j in 0 until m.length()) if (m.getString(j) == pkg) return g
        }
        return null
    }

    private fun membersOf(group: JSONObject): List<String> {
        val a = group.optJSONArray("members") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }

    /** The group's name in this TV's language; the backend keeps both. */
    private fun groupName(group: JSONObject): String? {
        fun pick(o: JSONObject): String? = (if (Locale.getDefault().language.startsWith("zh")) listOf("zh", "en")
        else listOf("en", "zh")).firstNotNullOfOrNull { o.optString(it).takeIf(String::isNotBlank) }
        return when (val n = group.opt("name")) {
            is JSONObject -> pick(n)
            is String -> n.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** How long the longest-running exception on this group or the whole TV still lasts. */
    private fun exceptionLeft(r: JSONObject, gid: String, nowMs: Long): Long? {
        val list = r.optJSONArray("exceptions") ?: return null
        var best: Long? = null
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val expires = e.optLong("expiresAt")
            if (expires <= nowMs) continue
            if (e.optString("scope") != "global" && e.optString("gid") != gid) continue
            val left = expires - nowMs
            if (best == null || left > best) best = left
        }
        return best
    }

    /** The enabled policies of one type ("time" / "quota"), in the order the backend sent them. */
    private fun enabledPolicies(r: JSONObject, type: String): List<JSONObject> {
        val ps = r.optJSONArray("policies") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until ps.length()) {
            val p = ps.optJSONObject(i) ?: continue
            if (p.optBoolean("enabled", true) && p.optString("type") == type) out.add(p)
        }
        return out
    }

    /**
     * Whether a policy touches this group at all (POLICY-DESIGN §1): one bound to no group
     * (`groups` empty or absent) applies to every group; one bound to groups applies to those.
     */
    private fun policyApplies(p: JSONObject, gid: String): Boolean {
        val gs = p.optJSONArray("groups") ?: return true
        if (gs.length() == 0) return true
        return (0 until gs.length()).any { gs.optString(it) == gid }
    }

    /**
     * Whether a quota policy caps the whole TV's day instead of one group's (POLICY-DESIGN §2.5):
     * true when it is bound to no group (`groups` empty or absent). Only meaningful after
     * [policyApplies] has said the policy touches this group at all.
     */
    private fun quotaIsWholeTv(p: JSONObject): Boolean {
        val gs = p.optJSONArray("groups") ?: return true
        return gs.length() == 0
    }

    /** One time policy's answer about [at]: inside a window (counting down to its end) or
     * outside one (counting up to the next opening). Null when the policy has no windows at all:
     * `windows: []` means the whole day is allowed (POLICY-DESIGN §1), so it answers nothing. */
    private class WindowState(val open: Boolean, val leftMs: Long)

    private fun windowState(config: JSONObject?, at: Moment): WindowState? {
        val wins = config?.optJSONArray("windows") ?: return null
        if (wins.length() == 0) return null
        val mins = at.minutes.toLong()
        val days = config.optJSONArray("weekdays")
        // An absent or empty weekdays list covers every day, matching the backend.
        fun applies(d: Int) = days == null || days.length() == 0 ||
            (0 until days.length()).any { days.optInt(it) == d }
        var openLeft = Long.MAX_VALUE
        var nextStart = Long.MAX_VALUE
        // A window covering `mins` may have opened yesterday (from > to crosses midnight) and the
        // next opening may be any day of the coming week, so scan a week of window starts.
        for (shift in -1..7) {
            val day = Math.floorMod(at.dayOfWeek - 1 + shift, 7) + 1   // 1=Mon .. 7=Sun
            if (!applies(day)) continue
            for (i in 0 until wins.length()) {
                val w = wins.optJSONObject(i) ?: continue
                val from = w.optString("from", "00:00").toMinutes()
                val to = w.optString("to", "00:00").toMinutes()
                if (from == to) continue                              // an empty window, like []
                val start = shift * (24L * 60) + from
                val end = if (to > from) start + (to - from) else start + (24 * 60 - from + to)
                if (shift <= 0 && mins in start until end) {
                    if (end - mins < openLeft) openLeft = end - mins
                } else if (start > mins && start < nextStart) {
                    nextStart = start
                }
            }
        }
        if (openLeft != Long.MAX_VALUE) return WindowState(true, openLeft * 60_000L)
        // Nothing ahead means the weekdays list only ever named days that cannot come (junk
        // config); blocking with no countdown is the honest answer for that.
        val wait = if (nextStart == Long.MAX_VALUE) 0L else (nextStart - mins) * 60_000L
        return WindowState(false, wait)
    }

    /** The daily cap one quota policy sets at [at], in ms; null when it sets none (minutes: null).
     * On a weekend day `weekendMinutes` replaces `minutes` when it is set. */
    private fun quotaLimitMs(p: JSONObject, at: Moment): Long? {
        val c = p.optJSONObject("config") ?: return null
        fun minutes(key: String): Int? = if (c.isNull(key)) null else c.optInt(key, -1).takeIf { it >= 0 }
        val weekday = minutes("minutes") ?: return null
        val limit = if (at.weekend) minutes("weekendMinutes") ?: weekday else weekday
        return limit * 60_000L
    }

    /** "21:30" to minutes past midnight; anything unreadable becomes midnight rather than a crash. */
    private fun String.toMinutes(): Int {
        val parts = split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return (h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59))
    }

    // ------------------------------------------------------------------- diagnostics (dump only)

    /**
     * Every number the rules are running on right now, written to stime_dump.txt beside the app's
     * own files for `adb pull` (ConfigReceiver's `stime_dump`; some Sonys keep Aerial out of the
     * logcat buffers, so the file is what matters and the Log.i lines are a convenience). The
     * cached rules, the day's tallies, the moment they are judged at, the answer for the app in
     * front, and each policy's intermediate values — the same helpers [statusOf] uses, spelled
     * out. Runs on the main thread from the broadcast, which is where the session fields live, so
     * reading them is safe. Debug only: nothing in normal use calls it, and it changes nothing.
     */
    fun dumpDiagnostics(context: Context): String {
        if (!enabled || !::app.isInitialized) return "stime_dump: screen time is off (no base URL / not initialized)"
        val out = StringBuilder()
        fun line(s: String) {
            out.append(s).append('\n')
            Log.i(TAG, s)
        }
        val now = CalendarBits.now()
        val at = Moment(now.hour, now.minute, now.dayOfWeek, System.currentTimeMillis())
        line("dump wall=${System.currentTimeMillis()} dayKey=$dayKey online=$online")
        line("moment: hour=${at.hour} minute=${at.minute} dayOfWeek=${at.dayOfWeek} weekend=${at.weekend}")
        line("rulesVersion=$rulesVersion device=$deviceId enrolled=${secret.isNotEmpty()} baseUrl=$baseUrl")
        line("usageEpoch cached=$usageEpoch served=$lastServedEpoch")

        val r = rules
        if (r == null) line("rules: null (this TV never reached the backend)")
        else {
            val gs = r.optJSONArray("groups")
            line("rules.groups=${gs?.length() ?: 0}")
            if (gs != null) for (i in 0 until gs.length()) {
                val g = gs.optJSONObject(i) ?: continue
                line("  group id=${g.optString("id")} blocked=${g.optBoolean("blocked")} members=${g.optJSONArray("members")?.length() ?: 0}")
            }
            val ps = r.optJSONArray("policies")
            line("rules.policies=${ps?.length() ?: 0} (key present=${ps != null})")
            if (ps != null) for (i in 0 until ps.length()) line("  policy ${ps.optJSONObject(i)}")
            val xs = r.optJSONArray("exceptions")
            if (xs != null) for (i in 0 until xs.length())
                line("  exception now=${at.nowMs} ${xs.optJSONObject(i)}")
        }

        line("todayMs: " + if (todayMs.isEmpty()) "(empty)"
            else todayMs.entries.joinToString(" ") { "${it.key}=${it.value}" })
        line("usedTotal=${usedTotal()} pending=${pending.size} screenOff=$screenOff frontPkg=$frontPkg " +
            "frontElapsedMs=" + if (frontPkg != null) SystemClock.elapsedRealtime() - frontSince else -1)

        val pkg = frontPkg
        if (pkg == null) line("status: nothing in front")
        else {
            val s = status(pkg)
            line(if (s == null) "status: pkg=$pkg null (not governed)"
            else "status: pkg=$pkg kind=${s.kind} groupId=${s.groupId} leftMs=${s.leftMs} blockedNow=${s.blockedNow} blocks=${s.blocks}")
            val group = r?.let { groupOf(it, pkg) }
            if (group == null) line("policy walk: $pkg is in no group, statusOf answers null for it")
            else {
                val gid = group.optString("id")
                val members = membersOf(group)
                line("policy walk: gid=$gid usedGroup=${usedFor(members)} usedTotal=${usedTotal()}")
                val ps = r.optJSONArray("policies") ?: JSONArray()
                for (i in 0 until ps.length()) {
                    val p = ps.optJSONObject(i) ?: continue
                    val applies = policyApplies(p, gid)
                    val on = p.optBoolean("enabled", true)
                    when (p.optString("type")) {
                        "time" -> {
                            val st = windowState(p.optJSONObject("config"), at)
                            line("  " + p.optString("id") + " time applies=$applies enabled=$on " +
                                "window=" + (st?.let { "open=${it.open} leftMs=${it.leftMs}" } ?: "no-windows(allow all day)"))
                        }
                        "quota" -> {
                            val whole = quotaIsWholeTv(p)
                            val limit = quotaLimitMs(p, at)
                            val used = if (whole) usedTotal() else usedFor(members)
                            line("  " + p.optString("id") + " quota applies=$applies enabled=$on wholeTv=$whole " +
                                "limitMs=" + (limit?.toString() ?: "no-cap") + " usedMs=$used " +
                                "leftMs=" + (limit?.let { (it - used).toString() } ?: "n/a"))
                        }
                        else -> line("  " + p.optString("id") + " type=" + p.optString("type") + " (unknown, ignored)")
                    }
                }
            }
        }

        val file = runCatching {
            // The point of the whole dump: a pullable file. External storage can be unavailable on
            // some firmwares, which must not make the broadcast crash.
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            java.io.File(dir, "stime_dump.txt").apply { writeText(out.toString()) }
        }.onFailure { Log.w(TAG, "cannot write stime_dump.txt", it) }.getOrNull()
        return "stime_dump: ${out.toString().trimEnd().lines().size} lines" +
            (file?.let { " -> ${it.absolutePath}" } ?: " (FILE WRITE FAILED, see: adb logcat -s $TAG)")
    }

    // --------------------------------------------------------------------- backend round trips

    private suspend fun tickLoop() {
        // Started so the very first pass does its round trip, whatever the clock says yet.
        var lastBeat = -TICK_MS
        while (true) {
            // Close the running session into the day tally so a TV left on all day keeps counting,
            // then push what accumulated. Doing it here keeps the main thread out of the network.
            val near = withContext(Dispatchers.Main) {
                // The poll is the safety net where a firmware drops a screen broadcast: the
                // state and the panel must agree before the session is settled, whichever missed.
                if (isInteractive(app) == screenOff) {
                    if (screenOff) noteScreenOn() else noteScreenOff()
                }
                closeFront(SystemClock.elapsedRealtime())
                watchForLimit()
                publishStatus()
                lastMinute()
            }
            val now = SystemClock.elapsedRealtime()
            // The heartbeat keeps its 30 s beat even while the decisions run every second: the
            // last minute needs a faster stop, not a faster round trip.
            if (secret.isEmpty()) enrol()
            else if (now - lastBeat >= TICK_MS) {
                lastBeat = now
                push(); pullRulesIfStale(); pushApps()
            }
            delay(if (near) FAST_TICK_MS else TICK_MS)
        }
    }

    /**
     * Whether the loop must look again within the second instead of the half minute: the front
     * app's rule crosses zero inside [FAST_WINDOW_MS] — quota, window closing, exception running
     * out, whichever answers — or it already blocks and the app is still in front, which means a
     * pull-back was dropped and retries belong on the fast beat. A TV with no rule in front (the
     * launcher itself, an app in no group) has nothing to time. Runs on the main thread from the
     * tick loop, like everything that touches frontPkg.
     */
    private fun lastMinute(): Boolean {
        val pkg = frontPkg ?: return false
        val s = status(pkg) ?: return false
        if (s.blocks) return true
        return s.leftMs in 1..FAST_WINDOW_MS
    }

    /**
     * The limit reached while the child is watching, not only at the next tile press. Runs on the
     * main thread from the tick loop, so it may touch frontPkg and call [onBlocked]. Aerial's own
     * time is never watched: onForeground() drops this package, and a null frontPkg clears the
     * memory so the same app is announced again next time it overruns.
     */
    private fun watchForLimit() {
        val pkg = frontPkg
        // Coming home (or the guard pulling us home) leaves nothing to judge; the parked block
        // stays: the window event that made frontPkg null may arrive before Aerial's own onResume
        // takes it for display, and it expires on its own if nobody ever shows it.
        if (pkg == null) { limitNotified = null; return }
        val block = check(pkg)
        if (block == null) { limitNotified = null; pendingBlock = null; return }
        // The set is asked home through the guard's system Home action on every look while the
        // stopped app is still in front: a firmware that dropped the first request keeps the app
        // playing silently (Android 10 background activity starts are dropped in silence, Sony
        // KD-65X9500H 2026-11-06 and again 2026-11-07 — the countdown ended, nothing moved, and
        // only pressing Home by hand explained why). The gate's extras cannot ride on a Home
        // press, so the block waits in [pendingBlock] for Aerial to show it once in front. A TV
        // with no guard bound has only the direct start, which the same firmwares may drop.
        pendingBlock = block
        pendingBlockAt = SystemClock.elapsedRealtime()
        if (!HomeGuardService.pressHomeForLimit()) onBlocked?.invoke(block)
        val key = block.pkg + "|" + block.reason.name
        if (key == limitNotified) return
        limitNotified = key
        reportBlock(block)                     // the parent sees it in the event log
        // FALLBACK-DESIGN §2: this very instant — the block decided, the pull-back and the gate
        // under way — is when the backend's guard needs the word. Every policy governing the
        // package reports, not just the tightest `statusOf` shows, because the backend watches
        // each strategy separately and one missed beat must not read as a lock that never came.
        reportStrategyEnds(block)
    }

    /**
     * The strategies that governed [b]'s package at the moment it stopped (FALLBACK-DESIGN §2's
     * enumeration). Waiting time policies are included here even though the heartbeat leaves them
     * out: the app is being stopped right now, and the policy that closed is one of the reasons.
     * Runs on the main thread, from [watchForLimit].
     */
    private fun governingBeatsNow(pkg: String): List<Beat> {
        val now = CalendarBits.now()
        val (_, beats) = governingOf(
            rules, pkg,
            Moment(now.hour, now.minute, now.dayOfWeek, System.currentTimeMillis()),
            usedGroup = { members -> usedFor(members) },
            usedTotal = { usedTotal() },
        ) ?: return emptyList()
        return beats
    }

    /** One `kind=strategy_end` event per governing strategy, §2: each with its own policyId. */
    private fun reportStrategyEnds(b: Block) {
        if (!enabled || secret.isEmpty()) return
        for (event in strategyEndEvents(b.pkg, b.reason, governingBeatsNow(b.pkg))) scope.launch(Dispatchers.IO) {
            request(
                "/api/tv/event", event.put("device", deviceId).put("secret", secret)
            )
        }
    }

    /**
     * The §2 event bodies for one block: one per policy governing the package at that moment,
     * `text` naming the Reason so the page reads *why* the lock landed. The set comes from
     * [governingOf], which is what makes "全发才不遗漏" hold: every strategy this package was
     * under reports, not just the one [statusOf] would have shown as tightest.
     */
    internal fun strategyEndEvents(pkg: String, reason: Reason, beats: List<Beat>): List<JSONObject> =
        beats.map {
            JSONObject()
                .put("kind", "strategy_end").put("policyId", it.policyId)
                .put("pkg", pkg).put("text", reason.name)
        }

    private suspend fun enrol() = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device", deviceId)
            .put("name", Settings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                ?: android.os.Build.MODEL)
            .put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("flavor", BuildConfig.FLAVOR)
            .put("enrollToken", BuildConfig.TV_MANAGER_TOKEN)
        val (code, res) = request("/api/tv/register", body)
        if (code == 200 && res.optString("secret").isNotEmpty()) {
            secret = res.getString("secret")
            val joined = res.optJSONObject("policy")
            stripPin(joined)                      // the first poll adopts it, at most 30s away
            rules = joined
            rulesVersion = rules?.optLong("rulesVersion") ?: 0
            online = true
            persist()
            Log.i(TAG, "enrolled as $deviceId")
        } else {
            Log.w(TAG, "enrolment failed http $code: ${res.opt("error")}")
        }
    }

    /**
     * Whether the last usage heartbeat carried a non-empty `activeStrategies` (FALLBACK-DESIGN §1).
     * An empty array is the message that clears the backend's guard records, so the heartbeat that
     * drops the last countdown must get out even from an otherwise-silent idle TV — this is what
     * keeps the "nothing to report" shortcut from swallowing it.
     */
    @Volatile private var strategiesOutstanding = false

    private suspend fun push() {
        // Snapshot the main-thread fields here, before leaving the main thread.
        val batch: List<JSONObject> = synchronized(pending) { ArrayList(pending) }
        val front = frontPkg
        val since = if (front != null) wallStart(SystemClock.elapsedRealtime(), frontSince) else 0L
        // The per-strategy heartbeat (FALLBACK-DESIGN §1): what the app in front is counting down
        // to, policy by policy. An empty array is a statement, not an omission — the backend clears
        // its records for this device on seeing one (§1: "空数组＝当前无活动策略").
        val strategies = if (front != null) activeStrategiesNow(front) else emptyList()
        // A TV that nobody is watching still has to say what version it runs, once per version;
        // and one that just lost its last countdown has to send the empty array that clears it.
        if (batch.isEmpty() && front == null && !strategiesOutstanding &&
            lastReportedCode == BuildConfig.VERSION_CODE.toLong()) return
        val body = JSONObject()
            .put("device", deviceId)
            .put("secret", secret)
            .put("sessions", JSONArray(batch))
            .put(
                "current",
                if (front != null) JSONObject().put("pkg", front).put("since", since)
                else JSONObject().put("pkg", JSONObject.NULL),
            )
            .put("activeStrategies", JSONArray(strategies.map { it.toJson() }))
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("flavor", BuildConfig.FLAVOR)
        val (code, res) = withContext(Dispatchers.IO) { request("/api/tv/usage", body) }
        when {
            code == 200 -> {
                synchronized(pending) { pending.removeAll(batch.toSet()) }
                lastReportedCode = BuildConfig.VERSION_CODE.toLong()
                // The backend has the array now; only the next one going empty owes a clearing beat.
                strategiesOutstanding = strategies.isNotEmpty()
                persist()
                // Deliberately NOT taking the reply's rulesVersion: a usage reply carries the number
                // but not the rules themselves, so treating it as "rules up to date" left the cache
                // stale for good — a group the parent edited never reached this TV. pullRulesIfStale
                // moves rulesVersion, because it is what receives the rules.
            }
            // The backend forgot this device (its data was reset): enrol again rather than
            // keep reporting into the void.
            code == 401 -> {
                secret = ""
                persist()
                Log.w(TAG, "device no longer known, re-enrolling")
            }
            else -> {
                online = false
                Log.w(TAG, "usage push http $code")
            }
        }
    }

    /**
     * Hands the backend what this TV has installed: the page's app shelf is made of this, and it
     * used to arrive by cable (collect_tv.py over adb). A list that changed — installed,
     * uninstalled, updated — goes at the next check; an unchanged one again every [APPS_TTL_MS],
     * so a backend whose data was reset fills back in. Nothing on the TV depends on the answer.
     *
     * With [force] the fingerprint is not consulted at all: that is the ADB hook (ConfigReceiver's
     * `report_apps`), which is also how a failure here is told apart from "nothing changed".
     */
    private suspend fun pushApps(force: Boolean = false) {
        if (!force) {
            val now = SystemClock.elapsedRealtime()
            if (appsCheckedAt != 0L && now - appsCheckedAt < APPS_CHECK_MS) return
            appsCheckedAt = now
        }
        val rows = runCatching { AppInventory.scan(app) }.getOrElse { e ->
            // Never let a scan kill the tick loop: usage reporting and the rules must keep working.
            Log.w(TAG, "app inventory scan failed", e)
            return
        }
        if (rows.isEmpty()) {
            Log.w(TAG, "app inventory came back empty; not reporting over the real list")
            return
        }
        val stamp = AppInventory.stamp(rows)
        if (!force && stamp == appsStamp && System.currentTimeMillis() - appsAt < APPS_TTL_MS) return
        val body = JSONObject()
            .put("device", deviceId)
            .put("secret", secret)
            .put("apps", AppInventory.toJson(rows))
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("flavor", BuildConfig.FLAVOR)
        val (code, res) = withContext(Dispatchers.IO) { request("/api/tv/apps", body) }
        when {
            code == 200 -> {
                appsStamp = stamp
                appsAt = System.currentTimeMillis()
                persist()
                Log.i(TAG, "app inventory reported: ${rows.size} apps")
            }
            // The backend forgot this device (its data was reset): enrol again, as for usage.
            code == 401 -> {
                secret = ""
                persist()
                Log.w(TAG, "device no longer known, re-enrolling")
            }
            else -> {
                appsCheckedAt = 0          // try again at the next tick, not half an hour later
                Log.w(TAG, "app inventory http $code: ${res.opt("error")}")
            }
        }
    }

    /**
     * Reports the app list now, fingerprint or not. The ADB hook (see ConfigReceiver): a TV whose
     * shelf looks wrong can be asked to report again without waiting for a check.
     */
    fun reportAppsNow() {
        if (!enabled || !::app.isInitialized) return
        scope.launch { pushApps(force = true) }
    }

    private suspend fun pullRulesIfStale() = withContext(Dispatchers.IO) {
        // flavor and versionCode ride along so the backend can answer "does this TV have something
        // to move to" in the same reply; the page then shows what each TV runs without another call.
        val path = "/api/tv/policy?device=" + URLEncoder.encode(deviceId, "UTF-8") +
            "&secret=" + URLEncoder.encode(secret, "UTF-8") +
            "&flavor=" + URLEncoder.encode(BuildConfig.FLAVOR, "UTF-8") +
            "&versionCode=" + BuildConfig.VERSION_CODE
        val (code, res) = get(path)
        if (code == 200) {
            val update = res.optJSONObject("update")
            res.remove("update") // the rules cache is the rules, not a snapshot of some offer
            // The page's "refresh the app list" rides on this same poll: the phone press is parked
            // on the backend's device record and answered here, so no adb is in the loop.
            val wantApps = res.optJSONObject("apps")?.optBoolean("wanted") == true
            res.remove("apps")
            // The backend owns the parent PIN now (户主, 2026-10-31). It rides on this poll as a
            // hash; the TV adopts it on the main thread below and otherwise keeps its own. Removed
            // here so it never lands in the cached rules a child could read off the device.
            val pinHash = res.optJSONObject("pin")?.optString("hash").orEmpty()
            res.remove("pin")
            // The usage epoch lives outside rulesVersion (POLICY-DESIGN §9): "reset today" moves
            // it while the rules stay put, so read it from every 200 reply — and remove it before
            // this object is cached as the rules, the same care as the PIN hash. The comparison
            // and any reset run on the main thread below, with the publish that follows.
            val epoch = servedEpoch(res)
            res.remove("usageEpoch")
            val v = res.optLong("rulesVersion")
            if (v != rulesVersion || rules == null) {
                rules = res
                rulesVersion = v
                persist()
                Log.i(TAG, "rules updated to v$v")
            }
            online = true
            withContext(Dispatchers.Main) {
                adoptBackendPin(pinHash)
                // A newer epoch means the parent pressed "reset today": drop this TV's stale day
                // before the publish below, so the pill never keeps a limit the backend zeroed.
                adoptUsageEpoch(epoch)
                Updates.noteOffer(app, update)
                // New rules can change what the app in front is allowed: republish at once rather
                // than wait for the next tick, so the pill never shows a limit that was lifted.
                publishStatus()
            }
            if (wantApps) pushApps(force = true)
        } else if (code == 401) {
            secret = ""
            persist()
        } else {
            online = false
        }
    }

    /**
     * The viewer typed the TV's own PIN at the gate, for [minutes] more of this group — or for the
     * rest of today when [minutes] is null. The backend turns that into a timed exception (the same
     * object the phone page adds and revokes), so the pill counts it down and the parent can take it
     * back. The PIN is the parent's say-so: unlike a request from the phone, this one is not charged
     * against the group's extend quota.
     */
    fun dayPass(groupId: String?, minutes: Int?, onDone: (Boolean) -> Unit) {
        if (!enabled || secret.isEmpty()) {
            onDone(false)
            return
        }
        scope.launch {
            var adoptedPin = ""
            var epochFromPass: Long? = null
            val ok = withContext(Dispatchers.IO) {
                val body = JSONObject().put("device", deviceId).put("secret", secret)
                if (groupId != null) body.put("groupId", groupId)
                if (minutes != null) body.put("minutes", minutes)
                val (code, res) = request("/api/tv/daypass", body)
                if (code == 200) {
                    res.optJSONObject("policy")?.let {
                        adoptedPin = stripPin(it)
                        // The same §9 epoch rides on this reply too: adopt it on the main thread
                        // below, and keep it out of the cached rules.
                        epochFromPass = servedEpoch(it)
                        it.remove("usageEpoch")
                        rules = it
                        rulesVersion = it.optLong("rulesVersion")
                    }
                    persist()
                    true
                } else {
                    Log.w(TAG, "day pass http $code: ${res.opt("error")}")
                    false
                }
            }
            withContext(Dispatchers.Main) {
                adoptBackendPin(adoptedPin)
                epochFromPass?.let { adoptUsageEpoch(it) }
                // The gate is closing on this answer; show the new countdown right away rather than
                // after the next tick, and let watchForLimit announce a fresh overrun if any.
                limitNotified = null
                if (ok) publishNow()
                onDone(ok)
            }
        }
    }

    /**
     * What became of an update the backend offered (update/Updates.kt): handed to the installer,
     * installed, failed, or turned down. The page lists these beside the rules events, so the owner
     * can see which TV is still on the old version and why.
     */
    fun reportUpdate(stage: String, from: Long, to: Long, versionName: String?, reason: String?) {
        if (!enabled || secret.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            request(
                "/api/tv/update/report", JSONObject()
                    .put("device", deviceId).put("secret", secret)
                    .put("stage", stage).put("fromCode", from).put("toCode", to)
                    .put("versionName", versionName ?: JSONObject.NULL)
                    .put("reason", reason ?: JSONObject.NULL)
            )
        }
    }

    /** A refusal the viewer saw, so the parent can read what happened on the TV. */
    fun reportBlock(b: Block) {
        if (!enabled || secret.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            request(
                "/api/tv/event", JSONObject()
                    .put("device", deviceId).put("secret", secret)
                    .put("kind", "blocked").put("pkg", b.pkg).put("groupId", b.groupId ?: JSONObject.NULL)
                    .put("text", b.reason.name)
            )
        }
    }

    /**
     * The backend's last resort (FALLBACK-DESIGN §4): a strategy passed its expected end and this
     * TV never sent the strategy_end, so its guard adb-broadcasts `stime_lock force` and we land
     * here. The viewer gets the same treatment as an ordinary overrun — the gate parked in
     * [pendingBlock] and the system Home press that brings Aerial back (the same path §4 insists
     * on: a background activity start from this process is dropped in silence, a Home press is
     * not), falling back to [onBlocked] only where no guard is bound. One pop, no day lock: the
     * TV's own judging takes over again afterwards, as the owner decided (设计取舍: 只弹一次锁).
     *
     * Runs on the main thread, from the broadcast receiver, which is where [frontPkg] and
     * [pendingBlock] live. The ack is the loop the page shows: kind=fallback_ack with the policyId
     * the backend locked for, so a silent drop (no such build, not enrolled) stays visible there.
     */
    fun forceLockFromBackend(policyId: String?) {
        val pkg = pendingBlock?.pkg ?: frontPkg ?: BuildConfig.APPLICATION_ID
        val block = Block(pkg, null, Reason.Fallback)
        pendingBlock = block
        pendingBlockAt = SystemClock.elapsedRealtime()
        if (!HomeGuardService.pressHomeForLimit()) onBlocked?.invoke(block)
        Log.i(TAG, "fallback lock popped (policy=$policyId, pkg=$pkg)")
        if (!enabled || secret.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            request(
                "/api/tv/event", JSONObject()
                    .put("device", deviceId).put("secret", secret)
                    .put("kind", "fallback_ack")
                    .put("policyId", policyId ?: JSONObject.NULL)
            )
        }
    }

    private fun request(path: String, body: JSONObject): Pair<Int, JSONObject> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            read(c)
        } catch (t: Throwable) {
            online = false
            Log.w(TAG, "request $path failed: $t")
            0 to JSONObject()
        } finally {
            c?.disconnect()
        }
    }

    private fun get(path: String): Pair<Int, JSONObject> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
            }
            read(c)
        } catch (t: Throwable) {
            online = false
            0 to JSONObject()
        } finally {
            c?.disconnect()
        }
    }

    private fun read(c: HttpURLConnection): Pair<Int, JSONObject> {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code in 200..299) online = true
        return code to runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }

    // ---------------------------------------------------------------------------- local storage

    private fun prefs(): SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun newDeviceId(p: SharedPreferences): String {
        val seed = runCatching { Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()?.takeIf { it.length >= 8 }
        val id = "tv-" + (seed ?: java.util.UUID.randomUUID().toString().replace("-", "").take(12))
        p.edit().putString("device_id", id).apply()
        return id
    }

    private fun persist() {
        if (!::app.isInitialized) return
        val arr = JSONArray()
        synchronized(pending) { for (s in pending) arr.put(s) }
        val today = JSONObject()
        for ((k, v) in todayMs) today.put(k, v)
        prefs().edit()
            .putString("device_id", deviceId)
            .putString("secret", secret)
            .putLong("usage_epoch", usageEpoch)
            .putString("day", dayKey)
            .putString("today", today.toString())
            .putString("pending", arr.toString())
            .putString("rules", rules?.toString() ?: "")
            .putLong("rules_version", rulesVersion)
            .putString("apps_stamp", appsStamp)
            .putLong("apps_at", appsAt)
            .apply()
    }

    private fun restore(p: SharedPreferences) {
        dayKey = p.getString("day", dayKey) ?: dayKey
        if (dayKey != dayKeyOf(System.currentTimeMillis())) {
            dayKey = dayKeyOf(System.currentTimeMillis())
            todayMs.clear()
        } else {
            runCatching { JSONObject(p.getString("today", "{}").orEmpty()) }.getOrNull()?.let { o ->
                for (k in o.keys()) todayMs[k] = o.getLong(k)
            }
        }
        runCatching { JSONArray(p.getString("pending", "[]").orEmpty()) }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) pending.add(a.getJSONObject(i))
        }
        rules = runCatching { JSONObject(p.getString("rules", "").orEmpty()) }.getOrNull()
        rulesVersion = p.getLong("rules_version", 0)
        usageEpoch = p.getLong("usage_epoch", 0)
        appsStamp = p.getString("apps_stamp", "").orEmpty()
        appsAt = p.getLong("apps_at", 0)
    }

    private fun dayKeyOf(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** Hour, minute and ISO day of week, kept in one place so window days and the weekend agree. */
    private object CalendarBits {
        data class Now(val hour: Int, val minute: Int, val dayOfWeek: Int)

        fun now(): Now {
            val c = java.util.Calendar.getInstance()
            val dow = c.get(java.util.Calendar.DAY_OF_WEEK)          // Sun=1 .. Sat=7
            return Now(
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1,
            )
        }
    }
}
