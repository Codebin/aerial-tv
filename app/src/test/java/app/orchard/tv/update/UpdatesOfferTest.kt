package app.orchard.tv.update

import app.orchard.tv.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * For a copy signed with the household key, the tv-manager on the NAS is the only thing it may
 * install over, so what an offer is *not* allowed to say matters as much as what it says
 * (local/tv-manager/UPGRADE-DESIGN.md §9). Every rejection here is one that would otherwise end as
 * "the TV just never offered the update", which is miserable to debug from a sofa.
 */
@RunWith(RobolectricTestRunner::class)
class UpdatesOfferTest {
    /** TEST-NET-1 (RFC 5737): stands in for the household NAS, and is nobody's address. */
    private val base = "http://192.0.2.10:9080"
    private val ahead = BuildConfig.VERSION_CODE + 1L
    private val hash = "a".repeat(64)

    private fun offer(mutate: JSONObject.() -> Unit = {}) = JSONObject()
        .put("available", true)
        .put("versionName", "1.68")
        .put("versionCode", ahead)
        .put("url", "$base/releases/aerial-guard-1.68-$ahead.apk")
        .put("size", 3_041_288)
        .put("sha256", hash)
        .put("notes", "把首页磁贴的圆角修回来了")
        .apply(mutate)

    private fun parsed(mutate: JSONObject.() -> Unit = {}) = Updates.parse(offer(mutate), base)

    @Test
    fun acceptsWhatTheBackendSends() {
        val o = parsed() ?: return fail("a well formed offer was turned down")
        assertEquals("1.68", o.versionName)
        assertEquals(ahead, o.versionCode)
        assertEquals(hash, o.sha256)
        assertEquals(3_041_288L, o.size)
        assertEquals("把首页磁贴的圆角修回来了", o.notes)
        assertEquals(false, o.forced)
        assertEquals(true, parsed { put("forced", true) }?.forced)
    }

    @Test
    fun nothingIsOffered() {
        assertNull(Updates.parse(null, base))
        assertNull(Updates.parse(JSONObject().put("available", false), base))
    }

    @Test
    fun refusesAServerThatIsNotThisHousehold() {
        // The whole point: a spoofed answer must not make Aerial fetch from somebody else's box.
        assertNull(parsed { put("url", "https://evil.example.com/releases/aerial-guard-1.68-1.apk") })
        assertNull(parsed { put("url", "https://aerial.qust.me/download/aerial-guard.apk") })
        assertNull(parsed { put("url", "") })
    }

    @Test
    fun refusesAPathThatEscapesTheReleaseDirectory() {
        assertNull(parsed { put("url", "$base/releases/../../etc/passwd") })
        assertNull(parsed { put("url", "$base/other/aerial-guard-1.68-1.apk") })
    }

    @Test
    fun refusesADigestThatIsNotOne() {
        assertNull(parsed { put("sha256", "") })
        assertNull(parsed { put("sha256", "deadbeef") })
        assertNull(parsed { put("sha256", "g".repeat(64)) })
        assertEquals(hash, parsed { put("sha256", hash.uppercase()) }?.sha256)  // case does not matter
    }

    @Test
    fun refusesAVersionNameTheWebsiteCouldHaveSentAsHtml() {
        // Upstream reads a plain text file off the web and this same shape is what keeps a captive
        // portal's login page from being taken for a version. The household backend is checked too.
        assertNull(parsed { put("versionName", "1.68 定制版") })
        assertNull(parsed { put("versionName", "") })
        assertEquals("1.10", parsed { put("versionName", "1.10") }?.versionName)
    }

    @Test
    fun refusesAnythingNotAheadOfThisBuild() {
        assertNull(parsed { put("versionCode", BuildConfig.VERSION_CODE) })
        assertNull(parsed { put("versionCode", BuildConfig.VERSION_CODE - 1) })
        assertNull(parsed { put("versionCode", 0) })
        assertNull(parsed { remove("versionCode") })
    }

    /**
     * The backend may flip `forced` a few minutes after enabling a release — that is how the
     * 1.79 (103) push went out on 2026-11-03, and a TV that had already prefetched the APK ignored
     * it and sat showing "new version" until the offer was re-sent. A change in what the parent
     * wants counts as a new offer.
     */
    @Test
    fun aForcedFlagFlipIsANewOffer() {
        val plain = parsed() ?: return fail("a well formed offer was turned down")
        val forced = parsed { put("forced", true) } ?: return fail("a forced offer was turned down")
        assertEquals(false, plain.forced)
        assertEquals(true, forced.forced)
        assertEquals(false, Updates.offerChanged(plain, plain))
        assertEquals(true, Updates.offerChanged(plain, forced))
        assertEquals(true, Updates.offerChanged(forced, plain))
        assertEquals(true, Updates.offerChanged(null, plain))
        assertEquals(false, Updates.offerChanged(null, null))
        val newer = parsed {
            put("versionName", "1.69"); put("versionCode", ahead + 1)
            put("url", "$base/releases/aerial-guard-1.69-${ahead + 1}.apk")
        } ?: return fail("a newer offer was turned down")
        assertEquals(true, Updates.offerChanged(forced, newer))
    }

    private fun fail(message: String): Nothing {
        org.junit.Assert.fail(message)
        throw AssertionError(message)
    }
}
