package app.orchard.tv.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * What this TV has, as the phone page lists it: one row per app Home can open, carrying the name
 * the TV itself shows in both languages.
 *
 * The page used to learn this from a cable: collect_tv.py read `pm list packages -3 -e` over adb
 * and each APK's label with aapt2. The TV now says it itself — [app.orchard.tv.screentime.ScreenTime]
 * posts these rows to /api/tv/apps — so the list follows an install or an uninstall without a Mac,
 * a cable or a cron.
 *
 * The set is the launchers', the same three queries AppCatalog loads Home's tiles with, not
 * "third-party packages only": a Sony set ships 云视听极光 (com.ktcp.tvvideo) on the system image,
 * and a third-party filter hid it from the page — the same way `pm list packages -3` hid it from
 * collect_tv.py. What a package flags as is not the question; whether the owner can open it is.
 * Aerial itself is left out: Home is not an app being watched.
 */
object AppInventory {
    private const val TAG = "AerialInventory"

    /** Xiaomi's app store declares only this category, not CATEGORY_LAUNCHER (AppCatalog). */
    private const val CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"

    /** One app. [code] is only for [stamp]: an app that was updated is worth reporting again. */
    data class Row(
        val pkg: String,
        val zh: String,
        val en: String,
        val code: Long = 0,
        /** Came on the system image: it can be limited, not uninstalled. */
        val system: Boolean = false,
    )

    /**
     * The apps Home can open, read off the package manager. Off the main thread: a firmware with a
     * hundred packages takes a few hundred milliseconds to name in two languages.
     */
    suspend fun scan(context: Context): List<Row> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // Labels are resources, so the answer depends on the configuration the manager answers
        // with. One manager per language, reused for every package, is the whole trick.
        val zhPm = pmFor(context, Locale.SIMPLIFIED_CHINESE)
        val enPm = pmFor(context, Locale.ENGLISH)
        // One call for every package. Asking getPackageInfo() per package afterwards was one more
        // binder trip each; this TV is old and its system_server is busy enough already.
        val all = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            .associateBy { it.packageName }
        val pkgs = LinkedHashSet<String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER, CATEGORY_APPSTORE_LAUNCHER)) {
            runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                    .forEach { pkgs += it.activityInfo.packageName }
            }
        }
        val rows = pkgs.asSequence()
            .filter { it != context.packageName }
            .mapNotNull { pkg ->
                val pi = all[pkg] ?: return@mapNotNull null
                // applicationInfo is a mutable property, so it is read once into a local.
                val ai = pi.applicationInfo ?: return@mapNotNull null
                if (!ai.enabled) return@mapNotNull null
                // The name the icon carries wins over the package name, but a package with no
                // label of its own is still listed: it is installed, and the page counts it.
                val en = labelOf(enPm, pkg) ?: labelOf(pm, pkg) ?: pkg
                Row(
                    pkg = pkg,
                    zh = labelOf(zhPm, pkg) ?: en,
                    en = en,
                    code = pi.lastUpdateTime,
                    system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.pkg }
            .toList()
        Log.i(TAG, "inventory: ${rows.size} apps")
        rows
    }

    /** The rows as /api/tv/apps carries them; the backend adds the category itself. */
    fun toJson(rows: List<Row>): JSONArray = JSONArray().apply {
        for (r in rows) {
            put(
                JSONObject()
                    .put("pkg", r.pkg)
                    .put("label", JSONObject().put("zh", r.zh).put("en", r.en))
                    .put("system", r.system)
            )
        }
    }

    /**
     * A fingerprint of the list: an app installed, uninstalled or updated changes it, so the TV
     * reports again at the next check instead of waiting for the fallback. Kept in the prefs so a
     * reboot does not needlessly re-send a list the backend already has.
     *
     * Deliberately not the labels' text: a label is a resource lookup that can answer differently
     * once (a firmware app right after a reboot), and a fingerprint that moves on its own re-sends
     * the same list every half hour.
     */
    fun stamp(rows: List<Row>): String =
        rows.sortedBy { it.pkg }.joinToString(",") { "${it.pkg}@${it.code}" }

    private fun pmFor(context: Context, locale: Locale): PackageManager = runCatching {
        val conf = Configuration(context.resources.configuration).apply { setLocale(locale) }
        context.createConfigurationContext(conf).packageManager
    }.getOrElse { context.packageManager }

    /** The app's own name in that manager's language; null when the package declares none. */
    private fun labelOf(pm: PackageManager, pkg: String): String? = runCatching {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() && it != pkg }
}
