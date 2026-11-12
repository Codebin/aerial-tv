package app.orchard.tv.screentime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * The standby fix (owner, 2026-11-12): a TV that went to standby kept its last app on the window
 * stack and kept counting it — one settings screen left up overnight racked up 14.5 h and spent
 * the whole-TV allowance before lunch. These tests drive the session bookkeeping across a
 * screen-off / screen-on cycle with the clock handed in as a parameter, so they do not wait on
 * Robolectric's clock the way the live TV waits on the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenTimeStandbyTest {
    private val minute = 60_000L

    /** Session bookkeeping is private main-thread state; reflection is how the test touches it,
     *  the way the standby fix itself touches it from the receiver. */
    private fun field(name: String): Field =
        ScreenTime::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun setFront(pkg: String?, since: Long) {
        field("frontPkg").set(ScreenTime, pkg)
        field("frontSince").setLong(ScreenTime, since)
    }

    private fun frontSince(): Long = field("frontSince").getLong(ScreenTime)

    @Suppress("UNCHECKED_CAST")
    private fun todayMs(): Map<String, Long> =
        HashMap(field("todayMs").get(ScreenTime) as HashMap<String, Long>)

    @Before fun cleanSlate() {
        ScreenTime.screenOff = false
        (field("pending").get(ScreenTime) as ArrayList<*>).clear()
        (field("todayMs").get(ScreenTime) as HashMap<*, *>).clear()
        setFront(null, 0L)
    }

    @After fun leaveNoState() {
        (field("todayMs").get(ScreenTime) as HashMap<*, *>).clear()
        (field("pending").get(ScreenTime) as ArrayList<*>).clear()
        setFront(null, 0L)
        ScreenTime.screenOff = false
    }

    @Test fun switchingOffSettlesExactlyTheWatchedStretch() {
        setFront("test.app", 10 * minute)             // watching since t = 10 min
        ScreenTime.noteScreenOff(11 * minute)        // the panel goes dark at t = 11 min
        assertEquals(minute, todayMs()["test.app"])  // exactly the watched minute is settled
        assertTrue(ScreenTime.screenOff)
    }

    @Test fun theDarkHoursBelongToNoViewer() {
        setFront("test.app", 10 * minute)
        ScreenTime.noteScreenOff(11 * minute)
        // Two hours of standby: the app may still sit on the window stack, but nobody watches a
        // dark TV — and waking must restart its stretch at the wake-up, not at the switch-off.
        ScreenTime.noteScreenOn(131 * minute)
        assertEquals(minute, todayMs()["test.app"])
        assertEquals(131 * minute, frontSince())
        // Watching one more minute after the wake-up costs exactly that minute.
        ScreenTime.noteScreenOff(132 * minute)
        assertEquals(2 * minute, todayMs()["test.app"])
    }

    @Test fun ticksDoNotAccumulateWhileThePanelIsDark() {
        setFront("test.app", 10 * minute)
        ScreenTime.noteScreenOff(11 * minute)
        // onForeground during standby (some firmware still moves the stack) may re-arm the clock,
        // but nothing settles until the panel wakes — the tick loop settles every 30 s otherwise.
        ScreenTime.noteScreenOff(71 * minute)        // a second switch-off changes nothing
        ScreenTime.noteScreenOn(72 * minute)
        assertEquals(minute, todayMs()["test.app"])
        // A second wake without a matching dark period is a no-op, not a re-anchored clock.
        ScreenTime.noteScreenOn(172 * minute)
        assertEquals(72 * minute, frontSince())
    }
}
