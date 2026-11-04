package app.orchard.tv.screentime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Which rule answers for an app, and how much time it leaves. The order is the backend's
 * verdict (POLICY-DESIGN.md §2, as revised by §10): day lock, exception, blocked group, time
 * policies, group-bound quota policies, whole-TV quota policies. The countdown pill over the app
 * reads the same answer, so a mismatch here would be visible to the child.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenTimeStatusTest {
    private val minute = 60_000L
    private val defaultLocale = Locale.getDefault()

    @Before fun pinLocale() = Locale.setDefault(Locale.ENGLISH)
    @After fun restoreLocale() = Locale.setDefault(defaultLocale)

    /** Monday, 20:00. */
    private val evening = ScreenTime.Moment(hour = 20, minute = 0, dayOfWeek = 1, nowMs = 1_000_000_000_000L)

    /** Saturday, 20:00 — a weekend day, so quota rules may pick weekendMinutes. */
    private val saturday = evening.copy(dayOfWeek = 6)

    private fun group(
        id: String,
        name: String = id,
        blocked: Boolean = false,
        members: List<String> = listOf("test.app"),
    ) = JSONObject()
        .put("id", id)
        .put("name", JSONObject().put("zh", name).put("en", name))
        .put("blocked", blocked)
        .put("members", JSONArray(members))

    private fun rules(
        groups: List<JSONObject> = listOf(group("g")),
        policies: List<JSONObject> = emptyList(),
        exceptions: List<JSONObject> = emptyList(),
    ) = JSONObject()
        .put("groups", JSONArray(groups))
        .put("policies", JSONArray(policies))
        .put("exceptions", JSONArray(exceptions))

    /** A time policy: allowed windows for the groups it is bound to ([] = every group). */
    private fun timePolicy(
        groups: List<String> = emptyList(),
        windows: List<Pair<String, String>> = emptyList(),
        weekdays: List<Int> = emptyList(),
        enabled: Boolean = true,
    ) = JSONObject()
        .put("id", "time-" + windows.size + "-" + weekdays.size)
        .put("name", JSONObject().put("en", "Window"))
        .put("type", "time")
        .put("enabled", enabled)
        .put("groups", JSONArray(groups))
        .put(
            "config", JSONObject()
                .put("windows", JSONArray(windows.map { JSONObject().put("from", it.first).put("to", it.second) }))
                .put("weekdays", JSONArray(weekdays)),
        )

    /** A quota policy: a daily minute cap ([] groups = the whole TV, null minutes = no cap). */
    private fun quotaPolicy(
        groups: List<String> = emptyList(),
        minutes: Int? = null,
        weekendMinutes: Int? = null,
        enabled: Boolean = true,
    ) = JSONObject()
        .put("id", "quota-" + minutes)
        .put("name", JSONObject().put("en", "Quota"))
        .put("type", "quota")
        .put("enabled", enabled)
        .put("groups", JSONArray(groups))
        .put(
            "config", JSONObject()
                .put("minutes", minutes ?: JSONObject.NULL)
                .put("weekendMinutes", weekendMinutes ?: JSONObject.NULL),
        )

    private fun exception(scope: String? = null, gid: String? = null, expiresInMs: Long) = JSONObject()
        .apply {
            put("scope", scope ?: if (gid != null) "group" else "global")
            if (gid != null) put("gid", gid)
            put("expiresAt", evening.nowMs + expiresInMs)
        }

    private fun status(
        r: JSONObject,
        pkg: String = "test.app",
        at: ScreenTime.Moment = evening,
        used: Long = 0,
        usedTotal: Long = used,
        groupUsed: Long = used,
    ) = ScreenTime.statusOf(r, pkg, at, usedGroup = { groupUsed }, usedTotal = { usedTotal })

    // ------------------------------------------------------------------ who is governed at all

    @Test fun anAppInNoGroupIsOnlyMeasured() {
        // Even a whole-TV quota that is long spent never stops an app nobody grouped.
        val r = rules(policies = listOf(quotaPolicy(minutes = 30)))
        assertNull(status(r, pkg = "other.app", usedTotal = 90 * minute))
    }

    @Test fun noRulesAtAllAllowsEverything() {
        assertNull(ScreenTime.statusOf(null, "test.app", evening, { 0 }, { 0 }))
    }

    @Test fun oldRulesWithoutPoliciesDontCrash() {
        // The v3 shape a stale cache or an old backend can still hand over: a global bedtime and a
        // group dailyMinutes, no policies key. Neither limits anything any more — but nothing here
        // may throw, and the group's blocked flag must still work.
        val r = JSONObject()
            .put(
                "groups", JSONArray(listOf(
                    group("g").put("dailyMinutes", 60).put("bedtimeExempt", false),
                )),
            )
            .put("global", JSONObject().put("bedtime", JSONObject().put("enabled", true).put("from", "00:00").put("to", "23:59")))
            .put("exceptions", JSONArray())
        assertNull(status(r, used = 90 * minute))
        val blocked = JSONObject()
            .put("groups", JSONArray(listOf(group("g", blocked = true))))
            .put("exceptions", JSONArray())
        assertEquals(ScreenTime.Kind.Blocked, status(blocked)!!.kind)
    }

    // ------------------------------------------------------------------------ time-window policies

    @Test fun aWindowInsideItsTimeCountsDownToTheEnd() {
        val r = rules(policies = listOf(timePolicy(windows = listOf("07:00" to "21:30"))))
        val s = status(r)!!
        assertEquals(ScreenTime.Kind.Window, s.kind)
        assertEquals(90 * minute, s.leftMs)    // 21:30 is 90 minutes after 20:00
        assertTrue(!s.blocks)
    }

    @Test fun aWindowOutsideBlocksUntilTheNextOneOpens() {
        val r = rules(policies = listOf(timePolicy(windows = listOf("07:00" to "18:00"))))
        val s = status(r)!!
        assertEquals(ScreenTime.Kind.Window, s.kind)
        assertTrue(s.blocks)
        // Tuesday 07:00 is 660 minutes after Monday 20:00.
        assertEquals(660 * minute, s.leftMs)
    }

    @Test fun aWindowCrossingMidnightCoversItsEvening() {
        val r = rules(policies = listOf(timePolicy(windows = listOf("21:30" to "06:30"))))
        // Monday 22:00, inside the evening start of the window; it ends at 06:30 tomorrow.
        val at = evening.copy(hour = 22, minute = 0)
        val s = status(r, at = at)!!
        assertEquals(ScreenTime.Kind.Window, s.kind)
        assertEquals(510 * minute, s.leftMs)
        assertTrue(!s.blocks)
    }

    @Test fun aWindowCrossingMidnightCoversItsMorningTail() {
        val r = rules(policies = listOf(timePolicy(windows = listOf("21:30" to "06:30"))))
        // Monday 02:00 is the tail of a window that opened Sunday night; 04:30 left.
        val at = evening.copy(hour = 2, minute = 0)
        val s = status(r, at = at)!!
        assertEquals(ScreenTime.Kind.Window, s.kind)
        assertEquals(270 * minute, s.leftMs)
        assertTrue(!s.blocks)
    }

    @Test fun aWindowOnlyAppliesOnItsWeekdays() {
        // Wednesday nights only: on Tuesday the app is outside any window, and the next one opens
        // Wednesday 07:00 — 660 minutes away.
        val r = rules(policies = listOf(timePolicy(windows = listOf("07:00" to "21:30"), weekdays = listOf(3))))
        val at = evening.copy(dayOfWeek = 2)
        val s = status(r, at = at)!!
        assertEquals(ScreenTime.Kind.Window, s.kind)
        assertTrue(s.blocks)
        assertEquals(660 * minute, s.leftMs)
        // On Wednesday itself 20:00 is inside the window.
        val inside = status(r, at = evening.copy(dayOfWeek = 3))!!
        assertEquals(ScreenTime.Kind.Window, inside.kind)
        assertTrue(!inside.blocks)
    }

    /**
     * The shared schedule table (POLICY-DESIGN §2) — the backend's verdict and the TV's statusOf
     * are held against these same rows. Convention, agreed across the three ends: `weekdays` names
     * the day a window **starts** (1=Mon .. 7=Sun), so a from>to window crossing midnight carries
     * its early-morning tail under that start day — a Monday 06:29 is only open if Sunday opened
     * the window — and the `to` instant itself is not open.
     */
    @Test fun theSharedScheduleTableForAMidnightWindowOnWeekdays() {
        val r = rules(
            groups = listOf(group("g_video")),
            policies = listOf(timePolicy(
                groups = emptyList(),                     // every group
                windows = listOf("21:30" to "06:30"),
                weekdays = listOf(1, 2, 3, 4, 5),         // weekday evenings, no weekend
            )),
        )
        fun expect(day: Int, hour: Int, min: Int, open: Boolean, leftMinutes: Long) {
            val at = ScreenTime.Moment(hour = hour, minute = min, dayOfWeek = day, nowMs = evening.nowMs)
            val s = status(r, at = at)!!
            assertEquals(ScreenTime.Kind.Window, s.kind)
            assertEquals(open, !s.blockedNow)
            assertEquals(open, !s.blocks)
            assertEquals(leftMinutes * minute, s.leftMs)
        }
        expect(day = 1, hour = 20, min = 0, open = false, leftMinutes = 90)     // ① waits for 21:30
        expect(day = 1, hour = 22, min = 0, open = true, leftMinutes = 510)     // ② ends Tue 06:30
        expect(day = 2, hour = 3, min = 0, open = true, leftMinutes = 210)      // ③ Monday window's tail
        expect(day = 2, hour = 7, min = 0, open = false, leftMinutes = 870)     // ④ waits for Tue 21:30
        expect(day = 6, hour = 10, min = 0, open = false, leftMinutes = 3570)   // ⑤ next start: Mon 21:30
        expect(day = 7, hour = 3, min = 0, open = false, leftMinutes = 2550)    // ⑥ no window opens Sunday
        expect(day = 1, hour = 6, min = 30, open = false, leftMinutes = 900)    // ⑦ `to` itself is closed
        expect(day = 1, hour = 6, min = 29, open = false, leftMinutes = 901)    // ⑧ the tail needs a Sunday start
    }

    // ---------------------------------------------------------------- usage epoch (POLICY-DESIGN §9)

    @Test fun anOldBackendWithoutUsageEpochChangesNothing() {
        // A reply from before §9 carries no field: that reads as 0, and 0 never outranks a fresh
        // TV's cached 0, so the local day keeps counting as it always did.
        val old = JSONObject().put("rulesVersion", 5).put("groups", JSONArray())
        assertEquals(0L, ScreenTime.servedEpoch(old))
        assertEquals(0L, ScreenTime.servedEpoch(null))
        assertTrue(!ScreenTime.epochResetsUsage(0, 0))
    }

    @Test fun onlyANewerUsageEpochRestartsTheLocalDay() {
        val p = JSONObject().put("usageEpoch", 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, ScreenTime.servedEpoch(p))
        assertTrue(ScreenTime.epochResetsUsage(1_700_000_000_000L, 1_600_000_000_000L))  // "reset today"
        assertTrue(!ScreenTime.epochResetsUsage(1_700_000_000_000L, 1_700_000_000_000L)) // every later poll, same epoch
        assertTrue(!ScreenTime.epochResetsUsage(0, 1_700_000_000_000L))                  // old backend afterwards
    }

    @Test fun emptyWindowsAllowTheWholeDay() {
        // POLICY-DESIGN §1: windows [] = allowed all day; such a policy answers nothing.
        val r = rules(policies = listOf(timePolicy(windows = emptyList())))
        assertNull(status(r))
    }

    @Test fun aWindowForNoGroupAppliesToEveryGroup() {
        val r = rules(policies = listOf(timePolicy(groups = emptyList(), windows = listOf("07:00" to "18:00"))))
        val s = status(r)!!
        assertEquals(ScreenTime.Kind.Window, s.kind)
        assertTrue(s.blocks)
    }

    @Test fun aWindowForAnotherGroupDoesNotApply() {
        val r = rules(
            groups = listOf(group("g"), group("other", members = listOf("other.app"))),
            policies = listOf(timePolicy(groups = listOf("other"), windows = listOf("07:00" to "18:00"))),
        )
        assertNull(status(r))
    }

    // --------------------------------------------------------------------------- quota policies

    @Test fun aGroupQuotaReportsTheTimeLeft() {
        val r = rules(policies = listOf(quotaPolicy(groups = listOf("g"), minutes = 60)))
        val s = status(r, groupUsed = 40 * minute)!!
        assertEquals(ScreenTime.Kind.GroupLimit, s.kind)
        assertEquals(20 * minute, s.leftMs)
        assertTrue(!s.blocks)
    }

    @Test fun aSpentGroupQuotaBlocks() {
        val r = rules(policies = listOf(quotaPolicy(groups = listOf("g"), minutes = 60)))
        val s = status(r, groupUsed = 61 * minute)!!
        assertEquals(ScreenTime.Kind.GroupLimit, s.kind)
        assertTrue(s.leftMs <= 0)
        assertTrue(s.blocks)
    }

    @Test fun aWholeTvQuotaMeasuresTheWholeTvNotTheGroup() {
        // groups [] = whole-TV (POLICY-DESIGN §2.5): the group's own 90 minutes are irrelevant.
        val r = rules(policies = listOf(quotaPolicy(minutes = 60)))
        val s = status(r, groupUsed = 90 * minute, usedTotal = 10 * minute)!!
        assertEquals(ScreenTime.Kind.TvLimit, s.kind)
        assertEquals(50 * minute, s.leftMs)
        assertTrue(!s.blocks)
        val spent = status(r, groupUsed = 90 * minute, usedTotal = 61 * minute)!!
        assertEquals(ScreenTime.Kind.TvLimit, spent.kind)
        assertTrue(spent.blocks)
    }

    @Test fun aQuotaBoundToAnotherGroupDoesNotApply() {
        val r = rules(
            groups = listOf(group("g"), group("other", members = listOf("other.app"))),
            policies = listOf(quotaPolicy(groups = listOf("other"), minutes = 30)),
        )
        assertNull(status(r, groupUsed = 90 * minute, usedTotal = 90 * minute))
    }

    @Test fun weekendMinutesReplaceTheCapOnWeekends() {
        val r = rules(policies = listOf(quotaPolicy(groups = listOf("g"), minutes = 60, weekendMinutes = 30)))
        // Monday: 45 of 60 used, 15 minutes left.
        val weekday = status(r, groupUsed = 45 * minute)!!
        assertEquals(15 * minute, weekday.leftMs)
        assertTrue(!weekday.blocks)
        // Saturday: the same 45 minutes are over the weekend cap of 30.
        val weekend = status(r, at = saturday, groupUsed = 45 * minute)!!
        assertEquals(ScreenTime.Kind.GroupLimit, weekend.kind)
        assertEquals(-15 * minute, weekend.leftMs)
        assertTrue(weekend.blocks)
    }

    @Test fun nullMinutesMeansNoLimit() {
        val r = rules(policies = listOf(quotaPolicy(minutes = null, weekendMinutes = 30)))
        assertNull(status(r, usedTotal = 600 * minute))
    }

    @Test fun aDisabledPolicyChangesNothing() {
        val r = rules(policies = listOf(
            timePolicy(windows = listOf("07:00" to "18:00"), enabled = false),
            quotaPolicy(minutes = 30, enabled = false),
        ))
        assertNull(status(r, usedTotal = 90 * minute))
    }

    // ------------------------------------------------------------------- day lock (POLICY-DESIGN §10)

    @Test fun aDayLockBeatsAnUncancelledException() {
        // The parent locked the whole day, so no exception — even one still on the books — can
        // revive a grouped app: §10 puts the lock at step 0, before the exception check.
        val r = rules(
            policies = listOf(quotaPolicy(groups = listOf("g"), minutes = 60)),
            exceptions = listOf(exception(gid = "g", expiresInMs = 30 * minute)),
        ).put("dayLockUntil", evening.nowMs + 120 * minute)
        val s = status(r, groupUsed = 10 * minute)!!
        assertEquals(ScreenTime.Kind.DayLock, s.kind)
        assertTrue(s.blocks)
        assertTrue(s.blockedNow)
        assertEquals(120 * minute, s.leftMs)    // the countdown runs to the lock's expiry
    }

    @Test fun aDayLockLeavesAnUngroupedAppAlone() {
        // The lock covers grouped apps only: an app nobody grouped is measured, never stopped —
        // while a grouped one is stopped by the very same rules.
        val r = rules().put("dayLockUntil", evening.nowMs + 120 * minute)
        assertNull(status(r, pkg = "other.app"))
        assertEquals(ScreenTime.Kind.DayLock, status(r)!!.kind)
    }

    @Test fun aDayLockEndsWhenItsInstantArrives() {
        // No cleanup job anywhere: the check is `dayLockUntil > nowMs`, so at the stamped midnight
        // itself — and every instant after — the lock is gone and the ordinary order resumes.
        val r = rules(policies = listOf(timePolicy(windows = listOf("07:00" to "21:30"))))
        assertEquals(ScreenTime.Kind.Window, status(r.put("dayLockUntil", evening.nowMs))!!.kind)
        assertEquals(ScreenTime.Kind.Window, status(r.put("dayLockUntil", evening.nowMs - minute))!!.kind)
        assertEquals(ScreenTime.Kind.Window, status(r.put("dayLockUntil", 0L))!!.kind)
    }

    @Test fun rulesWithoutADayLockFieldBehaveAsBefore() {
        // An old backend never sends dayLockUntil; optLong's 0 default reads as "not locked", so
        // the exception still answers exactly as it did before §10.
        val r = rules(exceptions = listOf(exception(gid = "g", expiresInMs = 30 * minute)))
        val s = status(r)!!
        assertEquals(ScreenTime.Kind.Passed, s.kind)
        assertTrue(!s.blocks)
    }

    // ------------------------------------------------------------------------ order of the rules

    @Test fun anExceptionBeatsEveryPolicy() {
        val r = rules(
            policies = listOf(
                timePolicy(windows = listOf("07:00" to "18:00")),      // 20:00 is outside
                quotaPolicy(minutes = 30),                              // and the TV used more
            ),
            exceptions = listOf(exception(scope = "global", expiresInMs = 30 * minute)),
        )
        val s = status(r, usedTotal = 60 * minute)!!
        assertEquals(ScreenTime.Kind.Passed, s.kind)
        assertEquals(30 * minute, s.leftMs)
        assertTrue(!s.blocks)
    }

    @Test fun aRunningExceptionIsReportedWithItsCountdown() {
        val r = rules(
            policies = listOf(quotaPolicy(groups = listOf("g"), minutes = 60)),
            exceptions = listOf(exception(gid = "g", expiresInMs = 15 * minute)),
        )
        val s = status(r, groupUsed = 60 * minute)!!
        assertEquals(ScreenTime.Kind.Passed, s.kind)
        assertEquals(15 * minute, s.leftMs)
    }

    @Test fun anExpiredExceptionChangesNothing() {
        val r = rules(
            policies = listOf(quotaPolicy(groups = listOf("g"), minutes = 60)),
            exceptions = listOf(exception(gid = "g", expiresInMs = -minute)),
        )
        val s = status(r, groupUsed = 60 * minute)!!
        assertEquals(ScreenTime.Kind.GroupLimit, s.kind)
        assertTrue(s.blocks)
    }

    @Test fun aBlockedGroupHasNothingToCount() {
        val r = rules(
            groups = listOf(group("g", blocked = true)),
            policies = listOf(timePolicy(windows = listOf("07:00" to "21:30"))),
        )
        val s = status(r)!!
        assertEquals(ScreenTime.Kind.Blocked, s.kind)
        assertEquals(0L, s.leftMs)
        assertTrue(s.blocks)
    }

    // --------------------------------------------------------------------- several rules at once

    @Test fun theTighterOfGroupAndTvQuotaWins() {
        val r = rules(policies = listOf(
            quotaPolicy(groups = listOf("g"), minutes = 60),
            quotaPolicy(minutes = 100),
        ))
        // Group has 20 min left, the TV 10: the TV is what stops the child first.
        val s = status(r, groupUsed = 40 * minute, usedTotal = 90 * minute)!!
        assertEquals(ScreenTime.Kind.TvLimit, s.kind)
        assertEquals(10 * minute, s.leftMs)
        // And the other way round.
        val s2 = status(r, groupUsed = 50 * minute, usedTotal = 60 * minute)!!
        assertEquals(ScreenTime.Kind.GroupLimit, s2.kind)
        assertEquals(10 * minute, s2.leftMs)
    }

    @Test fun aSpentGroupQuotaAnswersBeforeTheTvOne() {
        // POLICY-DESIGN §2: the group quota is judged before the whole-TV one, so it is the reason
        // the viewer sees even when the TV quota is deeper into the red.
        val r = rules(policies = listOf(
            quotaPolicy(groups = listOf("g"), minutes = 60),
            quotaPolicy(minutes = 60),
        ))
        val s = status(r, groupUsed = 65 * minute, usedTotal = 90 * minute)!!
        assertEquals(ScreenTime.Kind.GroupLimit, s.kind)
        assertEquals(-5 * minute, s.leftMs)
        assertTrue(s.blocks)
    }

    @Test fun theTightestCountdownAmongWindowAndQuotasWins() {
        val r = rules(policies = listOf(
            timePolicy(windows = listOf("07:00" to "20:20")),          // window ends in 20 min
            quotaPolicy(groups = listOf("g"), minutes = 60),           // quota has 20 min left too
        ))
        // Equal: the quota answers, as the stricter kind by the §2 order.
        val tie = status(r, groupUsed = 40 * minute)!!
        assertEquals(ScreenTime.Kind.GroupLimit, tie.kind)
        assertEquals(20 * minute, tie.leftMs)
        // A shorter window is the tighter countdown and wins for the pill.
        val r2 = rules(policies = listOf(
            timePolicy(windows = listOf("07:00" to "20:10")),          // 10 min left in the window
            quotaPolicy(groups = listOf("g"), minutes = 60),           // 20 min left in the quota
        ))
        val s = status(r2, groupUsed = 40 * minute)!!
        assertEquals(ScreenTime.Kind.Window, s.kind)
        assertEquals(10 * minute, s.leftMs)
        assertTrue(!s.blocks)
    }

    @Test fun theGroupNameFollowsTheTvLanguage() {
        val r = rules(
            groups = listOf(
                JSONObject().put("id", "g").put("name", JSONObject().put("zh", "看视频").put("en", "Video"))
                    .put("blocked", false).put("members", JSONArray(listOf("test.app"))),
            ),
            policies = listOf(quotaPolicy(groups = listOf("g"), minutes = 60)),
        )
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals("看视频", status(r, groupUsed = 10 * minute)!!.groupName)
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Video", status(r, groupUsed = 10 * minute)!!.groupName)
    }
}
