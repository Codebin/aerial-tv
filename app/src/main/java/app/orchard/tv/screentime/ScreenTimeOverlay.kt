package app.orchard.tv.screentime

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import app.orchard.tv.R

/**
 * A small pill in the top right corner over the app the viewer is using, saying which rule governs
 * it and how much time it leaves: "Video · 20 min left", "Allowed window · 2 h left". The family asked for
 * it to stay up the whole time the app plays, so it cannot live in Aerial's own window — the app is
 * in front then. The guard's accessibility service draws it instead: TYPE_ACCESSIBILITY_OVERLAY
 * needs no extra permission beyond the guard itself, which every managed TV has on already.
 *
 * It never takes input. FLAG_NOT_FOCUSABLE keeps the direction pad going to the app underneath
 * (the same reason the guard's own work must not steal keys), and FLAG_NOT_TOUCHABLE keeps taps
 * passing through. Nothing is added while Aerial itself is in front: ScreenTime reports null there,
 * and a pill over the home screen would only hide the wallpaper.
 *
 * Hidden below Android 5.1: TYPE_ACCESSIBILITY_OVERLAY arrived with it. Screen time is measured
 * there (the guard still sees window changes), only the countdown is missing.
 */
// The service is an AccessibilityService the guard owns; attach()/detach() follow its lifetime,
// and the overlay constant is inlined from API 22 while the guard runs on 5.0 too (see attach).
@SuppressLint("StaticFieldLeak", "InlinedApi")
object ScreenTimeOverlay {
    private const val TAG = "AerialScreenTimePill"

    private var service: AccessibilityService? = null
    private var manager: WindowManager? = null
    private var view: TextView? = null
    private var shown: ScreenTime.Status? = null

    /** When [shown] was announced; the pill counts down from its snapshot at its own second hand. */
    private var shownSince = 0L

    /**
     * Redraws the pill one second later. Only the last minute of a countdown ticks by seconds
     * (owner, 2026-11-05): before it the pill reads in whole minutes, which the announcements
     * every tick already keep correct, and the announcement that crosses into the last minute is
     * what starts this hand.
     */
    private val secondTick = Runnable { drawCurrent() }

    /**
     * A line drawn whether or not a managed app is in front, for checking the pill against a real
     * picture (ConfigReceiver's pill_preview). Never set in normal use.
     */
    private var preview: String? = null

    /** Called from the guard as its accessibility service connects and goes away. */
    fun attach(s: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        if (service === s) return
        detach()
        service = s
        manager = s.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ScreenTime.onStatus = { status ->
            when {
                status != null -> show(s, status)
                preview != null -> draw(s, preview!!)
                else -> hide()
            }
        }
        // ScreenTime may already have an app in front from before the guard rebound.
        ScreenTime.publishNow()
    }

    /**
     * Draw the pill with [text] now, or with null go back to what the rules say. Nothing is saved;
     * it is for looking at the pill's size and place on a real television.
     */
    fun showPreview(context: Context, text: String?) {
        preview = text
        val s = service
        if (s == null) {
            Log.w(TAG, "no guard service to draw a preview")
            return
        }
        if (text == null) {
            // Drop the preview and hand back to the rules. hide() first: publishStatus() stays
            // silent when the rule in front has not changed, and with no app in front that is a
            // null nobody would hear (publishNow resets `announced`, so nothing is told).
            hide()
            ScreenTime.publishNow()
        } else {
            draw(s, text)
        }
    }

    fun detach() {
        ScreenTime.onStatus = null
        hide()
        service = null
        manager = null
    }

    private fun show(s: AccessibilityService, status: ScreenTime.Status) {
        shown = status
        shownSince = SystemClock.elapsedRealtime()
        drawCurrent()
    }

    /**
     * Draws `shown` as it reads right now: the countdown of a live rule is its snapshot's leftMs
     * minus the time gone since. That runs exactly 1:1 for the rules that count down to a stop —
     * a quota only spends while its app is in front, which is when the pill exists at all; a
     * window's end and an exception's expiry are wall clock. When the seconds reach zero, the
     * tick loop (running every second by then) does the stopping; the pill may show 0:00 for a
     * beat before it goes away.
     */
    private fun drawCurrent() {
        val s = service ?: return
        val st = shown ?: return       // a preview took the pill over, or it was detached
        val left = (st.leftMs - (SystemClock.elapsedRealtime() - shownSince)).coerceAtLeast(0)
        val live = if (countingDown(st)) st.copy(leftMs = left) else st
        val text = label(s, live)
        if (text.isNullOrEmpty()) {
            hide()
            return
        }
        draw(s, text)
        val v = view ?: return
        v.removeCallbacks(secondTick)
        // The seconds hand runs only for the last minute, wherever that minute comes from: a
        // quota spent down to it, a window about to close, an exception about to run out.
        if (countingDown(live) && live.leftMs < 60_000) v.postDelayed(secondTick, 1_000)
    }

    /**
     * Whether the pill's line carries a countdown that walks with the clock. A rule that blocks
     * now shows why, not how long (its leftMs counts to a *release* — the next window, midnight —
     * and must not tick down on the pill), and "Time's up" has nothing left to count.
     */
    private fun countingDown(s: ScreenTime.Status): Boolean = when {
        s.blocks -> false
        s.kind == ScreenTime.Kind.Window || s.kind == ScreenTime.Kind.Passed -> true
        else -> s.leftMs > 0
    }

    private fun draw(s: AccessibilityService, text: String) {
        val v = view ?: makeView(s).also { add(it) } ?: return
        if (v.text.toString() != text) v.text = text
    }

    private fun hide() {
        shown = null
        val v = view ?: return
        view = null
        runCatching { manager?.removeView(v) }.onFailure { Log.w(TAG, "could not remove the pill: $it") }
    }

    private fun add(v: TextView) {
        val wm = manager ?: return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Level with Aerial's own status pill, clear of the overscan margin those TVs leave, but
            // tucked into the corner: the family watches films under it, so it takes as little of
            // the picture as it can (owner: "smaller, it must not get in the way of watching").
            x = v.context.dp(16f)
            y = v.context.dp(10f)
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess { view = v }
            .onFailure { Log.w(TAG, "could not show the pill: $it") }
    }

    private fun makeView(ctx: Context): TextView = TextView(ctx).apply {
        // Small: the pill sits over the film for as long as the app plays, so it is sized to be read
        // at a glance from the sofa and to cover a corner, not a fifth of the picture.
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val h = ctx.dp(9f)
        setPadding(h, ctx.dp(4f), h, ctx.dp(4f))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(11f).toFloat()
            // 40% black, so the film underneath stays readable through it.
            setColor(0x66000000)
            setStroke(ctx.dp(1f), 0x26FFFFFF)
        }
    }

    /** The one line the viewer reads, or null when this rule has nothing to count down. */
    private fun label(ctx: Context, s: ScreenTime.Status): String? {
        val name = s.groupName ?: ctx.getString(R.string.st_pill_app)
        val left = s.leftMs
        return when (s.kind) {
            ScreenTime.Kind.Passed ->
                ctx.getString(R.string.st_pill_pass_left, duration(ctx, left))
            ScreenTime.Kind.Blocked ->
                ctx.getString(R.string.st_pill_group_off, name)
            // A day lock always blocks and the app gets pulled back to Home where the gate card is
            // what the viewer reads, so the pill is only a fallback here — same wording as the
            // card, no countdown (POLICY-DESIGN §10).
            ScreenTime.Kind.DayLock ->
                ctx.getString(R.string.st_gate_reason_daylock)
            ScreenTime.Kind.Window ->
                if (s.blockedNow) ctx.getString(R.string.st_gate_reason_window)
                else ctx.getString(R.string.st_pill_window_left, duration(ctx, left))
            ScreenTime.Kind.GroupLimit ->
                if (left > 0) ctx.getString(R.string.st_pill_group_left, name, duration(ctx, left))
                else ctx.getString(R.string.st_pill_used_up)
            ScreenTime.Kind.TvLimit ->
                if (left > 0) ctx.getString(R.string.st_pill_tv_left, duration(ctx, left))
                else ctx.getString(R.string.st_pill_used_up)
        }
    }

    /**
     * How a countdown reads: minutes ("1 h 20 min", "45 min") until the last minute, then it ticks
     * seconds (0:59, 0:45, 0:00) — for every time policy alike, owner 2026-11-05: a quota, a window
     * closing, an exception running out, all tick their last minute in seconds because that is
     * the minute the TV judges every second.
     */
    private fun duration(ctx: Context, ms: Long): String {
        val total = ms / 60_000
        if (total < 1) return ctx.getString(R.string.st_pill_dur_ms, 0, (ms / 1_000).coerceIn(0, 59))
        val h = total / 60
        val m = total % 60
        return when {
            h > 0 && m > 0 -> ctx.getString(R.string.st_pill_dur_hm, h, m)
            h > 0 -> ctx.getString(R.string.st_pill_dur_h, h)
            else -> ctx.getString(R.string.st_pill_dur_m, m)
        }
    }

    private fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
