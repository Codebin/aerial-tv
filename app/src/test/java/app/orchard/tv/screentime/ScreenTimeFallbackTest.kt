package app.orchard.tv.screentime

import app.orchard.tv.guard.HomeGuardService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The fallback loop's TV side (FALLBACK-DESIGN §1/§2/§4): what the usage heartbeat says it is
 * counting down to, what gets reported the moment a lock lands, and what the backend's adb-popped
 * lock does here. The heartbeat carries one entry per *active* strategy — not just the tightest
 * one the pill shows — because the backend guards each policy separately and one missed beat must
 * not read as a lock that never came.
 *
 * Robolectric only for the org.json implementations, as in ScreenTimeStatusTest; nothing here
 * touches the network: ScreenTime is never init'ed, so every reporter stops at its
 * enabled/secret guard before any request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenTimeFallbackTest {
    private val minute = 60_000L
    private val defaultLocale = Locale.getDefault()

    @Before fun pinLocale() = Locale.setDefault(Locale.ENGLISH)

    @After fun cleanUp() {
        Locale.setDefault(defaultLocale)
        ScreenTime.onBlocked = null
        // Drop any block the last test parked, so none leaks into the next.
        ScreenTime.takePendingBlock(maxAgeMs = 0)
    }

    /** Monday, 20:00 — the same moment ScreenTimeStatusTest judges at. */
    private val evening = ScreenTime.Moment(hour = 20, minute = 0, dayOfWeek = 1, nowMs = 1_000_000_000_000L)

    private fun group(
        id: String = "g",
        blocked: Boolean = false,
        members: List<String> = listOf("test.app"),
    ) = JSONObject()
        .put("id", id)
        .put("name", JSONObject().put("en", id))
        .put("blocked", blocked)
        .put("members", JSONArray(members))

    private fun rules(
        groups: List<JSONObject> = listOf(group()),
        policies: List<JSONObject> = emptyList(),
        exceptions: List<JSONObject> = emptyList(),
    ) = JSONObject()
        .put("groups", JSONArray(groups))
        .put("policies", JSONArray(policies))
        .put("exceptions", JSONArray(exceptions))

    private fun timePolicy(
        id: String,
        groups: List<String> = emptyList(),
        windows: List<Pair<String, String>> = emptyList(),
    ) = JSONObject()
        .put("id", id).put("type", "time").put("enabled", true)
        .put("groups", JSONArray(groups))
        .put("config", JSONObject().put("windows", JSONArray(windows.map { JSONObject().put("from", it.first).put("to", it.second) })))

    private fun quotaPolicy(
        id: String,
        groups: List<String> = emptyList(),
        minutes: Int? = null,
    ) = JSONObject()
        .put("id", id).put("type", "quota").put("enabled", true)
        .put("groups", JSONArray(groups))
        .put("config", JSONObject().put("minutes", minutes ?: JSONObject.NULL).put("weekendMinutes", JSONObject.NULL))

    private fun exception(gid: String, expiresInMs: Long) = JSONObject()
        .put("scope", "group").put("gid", gid).put("expiresAt", evening.nowMs + expiresInMs)

    private fun active(
        r: JSONObject?,
        pkg: String = "test.app",
        at: ScreenTime.Moment = evening,
        groupUsed: Long = 0,
        usedTotal: Long = groupUsed,
    ) = ScreenTime.activeStrategiesOf(r, pkg, at, usedGroup = { groupUsed }, usedTotal = { usedTotal })

    private fun governed(
        r: JSONObject?,
        pkg: String = "test.app",
        at: ScreenTime.Moment = evening,
        groupUsed: Long = 0,
        usedTotal: Long = groupUsed,
    ) = ScreenTime.governingOf(r, pkg, at, usedGroup = { groupUsed }, usedTotal = { usedTotal })

    // ------------------------------------------------------------- §1: what rides the heartbeat

    @Test fun aQuotaCountingDownRidesTheHeartbeatWithItsInstant() {
        // 60 minutes a day, 40 used: the heartbeat's expectedEndMs is the beat's own wall clock
        // plus the 20 left (§1: "quota 的 expectedEndMs = 心跳时刻 + leftMs，每跳重算").
        val r = rules(policies = listOf(quotaPolicy("p_q", groups = listOf("g"), minutes = 60)))
        val a = active(r, groupUsed = 40 * minute).single()
        assertEquals("p_q", a.id)
        assertEquals("quota", a.kind)
        assertEquals("g", a.gid)
        assertEquals("test.app", a.pkg)
        assertEquals(evening.nowMs + 20 * minute, a.expectedEndMs)
    }

    @Test fun aTimePolicyReportsTheAbsoluteInstantItsWindowCloses() {
        // Open 07:00–21:30, now 20:00 Monday: the window closes 90 minutes from the beat, at an
        // absolute wall clock the backend can compare against its own (§1).
        val r = rules(policies = listOf(timePolicy("p_t", windows = listOf("07:00" to "21:30"))))
        val a = active(r).single()
        assertEquals("p_t", a.id)
        assertEquals("time", a.kind)
        assertEquals(evening.nowMs + 90 * minute, a.expectedEndMs)
    }

    @Test fun everyActiveStrategyRidesNotJustTheTightest() {
        // statusOf shows one pill; the heartbeat must carry both, or the backend guards a policy
        // blindly the moment the tighter one is extended (§2: "全发才不遗漏").
        val r = rules(policies = listOf(
            timePolicy("p_t", windows = listOf("07:00" to "20:10")),     // closes in 10 min
            quotaPolicy("p_q", groups = listOf("g"), minutes = 60),      // 20 min left
        ))
        val ids = active(r, groupUsed = 40 * minute).map { it.id }.sorted()
        assertEquals(listOf("p_q", "p_t"), ids)
        // And the pill still shows only the tightest one.
        assertEquals(ScreenTime.Kind.Window, ScreenTime.statusOf(r, "test.app", evening, { 40 * minute }, { 40 * minute })!!.kind)
    }

    @Test fun aSpentQuotaKeepsRidingWithAPassingInstant() {
        // The point of the fallback: a TV whose local stop was dropped keeps the backend watching,
        // expectedEndMs already in the past, until the lock lands or the guard adb-pops one.
        val r = rules(policies = listOf(quotaPolicy("p_q", groups = listOf("g"), minutes = 60)))
        val a = active(r, groupUsed = 61 * minute).single()
        assertEquals("p_q", a.id)
        assertEquals(evening.nowMs - minute, a.expectedEndMs)
    }

    @Test fun aClosedWindowIsNotCountingDownToAStop() {
        // Outside its windows a time policy counts UP to the next opening: no "expected end" to
        // guard, and the app is stopped now rather than when a beat passes zero (§1 semantics).
        val r = rules(policies = listOf(timePolicy("p_t", windows = listOf("07:00" to "18:00"))))
        assertTrue(active(r).isEmpty())
        // But it IS governing — strategy_end reports it the moment the stop lands (§2).
        val (_, beats) = governed(r)!!
        assertEquals(listOf("p_t"), beats.map { it.policyId })
        assertTrue(beats.single().waiting)
    }

    @Test fun dayLockAndExceptionAndBlockedGroupSendNoStrategies() {
        // §1: "DayLock、例外（Passed）不是「等待归零」的倒计时，不进 activeStrategies." The same
        // three take-overs also leave nothing for strategy_end to name (§4's ack is separate).
        val withQuota = listOf(quotaPolicy("p_q", groups = listOf("g"), minutes = 60))
        val locked = rules(policies = withQuota).put("dayLockUntil", evening.nowMs + minute)
        val passed = rules(policies = withQuota, exceptions = listOf(exception("g", 30 * minute)))
        val shut = rules(groups = listOf(group(blocked = true)), policies = withQuota)
        for (r in listOf(locked, passed, shut)) {
            assertTrue(active(r).isEmpty())
            assertNull(governed(r))
        }
    }

    @Test fun anUngroupedAppAndNoRulesSendNothing() {
        val r = rules(policies = listOf(quotaPolicy("p_q", minutes = 60)))
        assertTrue(active(r, pkg = "other.app", usedTotal = 90 * minute).isEmpty())
        assertTrue(active(null).isEmpty())
        assertTrue(active(rules(groups = emptyList()), pkg = "test.app").isEmpty())
    }

    @Test fun theHeartbeatEntryCarriesTheAgreedFieldNames() {
        // The backend parses exactly these keys (FALLBACK-DESIGN §1); renaming one without the
        // other side would silently stop the guard, so the shape is pinned here.
        val r = rules(policies = listOf(quotaPolicy("p_q", groups = listOf("g"), minutes = 60)))
        val j = active(r, groupUsed = 40 * minute).single().toJson()
        assertEquals(setOf("id", "kind", "gid", "pkg", "expectedEndMs"), JSONObject(j.toString()).keys().asSequence().toSet())
        assertEquals("p_q", j.getString("id"))
        assertEquals("quota", j.getString("kind"))
        assertEquals("g", j.getString("gid"))
        assertEquals("test.app", j.getString("pkg"))
        assertEquals(evening.nowMs + 20 * minute, j.getLong("expectedEndMs"))
    }

    @Test fun aWholeTvQuotaIsQuotaTooForTheGroupMember() {
        // groups [] = the whole TV (POLICY-DESIGN §2.5): it still governs the grouped app, so it
        // still rides the heartbeat, as quota.
        val r = rules(policies = listOf(quotaPolicy("p_tv", minutes = 60)))
        val a = active(r, usedTotal = 50 * minute).single()
        assertEquals("p_tv", a.id)
        assertEquals("quota", a.kind)
        assertEquals(evening.nowMs + 10 * minute, a.expectedEndMs)
    }

    @Test fun aPolicyForAnotherGroupIsNotActive() {
        val r = rules(
            groups = listOf(group("g"), group("other", members = listOf("other.app"))),
            policies = listOf(quotaPolicy("p_o", groups = listOf("other"), minutes = 30)),
        )
        assertTrue(active(r).isEmpty())
    }

    // ---------------------------------------------------------------- §2: the end-of-strategy word

    @Test fun everyGoverningStrategyEndsByItsOwnEvent() {
        // One block, every policy under it: kind, policyId, pkg and the Reason name (§2's shape).
        val beats = listOf(
            ScreenTime.Beat("p_q", ScreenTime.Kind.GroupLimit, -minute, waiting = false),
            ScreenTime.Beat("p_t", ScreenTime.Kind.Window, 660 * minute, waiting = true),
        )
        val events = ScreenTime.strategyEndEvents("test.app", ScreenTime.Reason.GroupLimit, beats)
        assertEquals(2, events.size)
        val q = events.first { it.getString("policyId") == "p_q" }
        assertEquals("strategy_end", q.getString("kind"))
        assertEquals("test.app", q.getString("pkg"))
        assertEquals("GroupLimit", q.getString("text"))
        // The window policy that stopped the app at its close reports too, waiting or not.
        assertEquals("strategy_end", events.last().getString("kind"))
        assertEquals("p_t", events.last().getString("policyId"))
    }

    @Test fun strategyEndCarriesTheReasonThatLandedTheBlock() {
        val events = ScreenTime.strategyEndEvents(
            "test.app", ScreenTime.Reason.Window,
            listOf(ScreenTime.Beat("p_t", ScreenTime.Kind.Window, 0, waiting = true)),
        )
        assertEquals("Window", events.single().getString("text"))
    }

    // ------------------------------------------------------- §4: the backend's adb-popped lock

    @Test fun forceLockFromBackendParksAFallbackBlock() {
        // With no guard bound (this is a bare JVM) the block must still land on [onBlocked], the
        // same fallback the overrun path uses; and it must wait in takePendingBlock for Home.
        var shown: ScreenTime.Block? = null
        ScreenTime.onBlocked = { shown = it }
        assertTrue(HomeGuardService.diagnosticState().contains("bound=false"))
        ScreenTime.forceLockFromBackend("p_q")
        assertEquals(ScreenTime.Reason.Fallback, shown?.reason)
        assertNotNull(shown?.pkg)
        val parked = ScreenTime.takePendingBlock()
        assertNotNull(parked)
        assertEquals(ScreenTime.Reason.Fallback, parked!!.reason)
        assertNull(ScreenTime.takePendingBlock())      // taken once, not repeated
    }

    @Test fun aStaleFallbackBlockExpiresLikeAnyOther() {
        // Popped while the TV slept; a card about last night's overrun must not ambush today.
        ScreenTime.onBlocked = {}
        ScreenTime.forceLockFromBackend(null)
        assertNull(ScreenTime.takePendingBlock(maxAgeMs = -1L))
    }

    @Test fun fallbackIsAnUnknownReasonNoOlderIntentProduces() {
        // MainActivity parses EXTRA_LIMIT_REASON with valueOf and falls back to GroupLimit for
        // names an older build never wrote; the new name must round-trip exactly.
        assertEquals(ScreenTime.Reason.Fallback, ScreenTime.Reason.valueOf("Fallback"))
    }
}
