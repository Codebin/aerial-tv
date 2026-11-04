package app.orchard.tv.apps

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the TV reports: the rows the page's app shelf is built from, and the fingerprint that
 * decides whether the backend needs them again. The page reads data/apps/<device>.json, the file
 * collect_tv.py used to write over adb; /api/tv/apps now carries the same rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppInventoryTest {
    private fun row(pkg: String, zh: String = "酷喵", en: String = "Youku", code: Long = 1) =
        AppInventory.Row(pkg, zh, en, code)

    @Test
    fun rowsCarryThePackagesTwoNames() {
        val json: JSONArray = AppInventory.toJson(listOf(row("com.cibn.tv", "CIBN酷喵", "CIBN Youku")))
        assertEquals(1, json.length())
        val o = json.getJSONObject(0)
        assertEquals("com.cibn.tv", o.getString("pkg"))
        assertEquals("CIBN酷喵", o.getJSONObject("label").getString("zh"))
        assertEquals("CIBN Youku", o.getJSONObject("label").getString("en"))
    }

    /** A firmware app the owner can open (Sony's 云视听极光, com.ktcp.tvvideo) is reported too. */
    @Test
    fun aSystemAppIsMarkedAsOne() {
        val json = AppInventory.toJson(listOf(row("com.ktcp.tvvideo").copy(system = true)))
        assertEquals(true, json.getJSONObject(0).getBoolean("system"))
        assertEquals(false, AppInventory.toJson(listOf(row("com.cibn.tv"))).getJSONObject(0).getBoolean("system"))
    }

    @Test
    fun installingAnAppChangesTheFingerprint() {
        val before = AppInventory.stamp(listOf(row("a.b"), row("c.d")))
        val after = AppInventory.stamp(listOf(row("a.b"), row("c.d"), row("e.f")))
        assertNotEquals(before, after)
    }

    @Test
    fun anUpdateChangesTheFingerprint() {
        val before = AppInventory.stamp(listOf(row("a.b", code = 1)))
        val after = AppInventory.stamp(listOf(row("a.b", code = 2)))
        assertNotEquals(before, after)
    }

    /** The package manager's order is not guaranteed, so the fingerprint must not depend on it. */
    @Test
    fun theSameAppsInAnotherOrderAreTheSameFingerprint() {
        assertEquals(
            AppInventory.stamp(listOf(row("a.b"), row("c.d"))),
            AppInventory.stamp(listOf(row("c.d"), row("a.b"))),
        )
    }

    /** A label's text is not part of the fingerprint: it can answer differently on its own. */
    @Test
    fun aRenamedAppKeepsTheFingerprint() {
        assertEquals(
            AppInventory.stamp(listOf(row("a.b", zh = "旧名"))),
            AppInventory.stamp(listOf(row("a.b", zh = "新名"))),
        )
    }
}
