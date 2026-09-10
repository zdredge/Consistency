package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * What the 04:00 job changes, and — more importantly — what it leaves alone.
 *
 * **This decides the primary metric.** Nothing in the app has ever set `CheckInState.MISSED`, so
 * until this rule exists a check-in the user never answered stays `PENDING` for ever and response
 * rate cannot fall. A boundary that is one day out here does not crash anything; it produces a
 * plausible wrong percentage, which is the failure mode this whole module exists to prevent.
 */
class RolloverPlannerTest {

    private val today = LocalDate.of(2026, 9, 10)
    private val now: Instant = Instant.parse("2026-09-10T08:00:00Z")

    private fun checkIn(
        day: LocalDate,
        slot: Slot = Slot.NIGHT,
        state: CheckInState = CheckInState.PENDING,
    ) = CheckIn(day = day, slot = slot, state = state)

    private fun plan(vararg checkIns: CheckIn) =
        RolloverPlanner.plan(today = today, checkIns = checkIns.toList(), now = now)

    // ---- The grace boundary -------------------------------------------------------------------

    @Test
    @DisplayName("today's unanswered check-in is not missed - the day is not over")
    fun todayIsSafe() {
        assertTrue(plan(checkIn(today)).checkInsToMiss.isEmpty())
    }

    @Test
    @DisplayName("3.2 - yesterday's is not missed either, because backfill runs to the end of today")
    fun yesterdayIsStillInGrace() {
        // The boundary that matters. Marking yesterday missed would contradict the banner, which is
        // still offering it, and would break a run the user could still have saved (4.3).
        assertTrue(plan(checkIn(today.minusDays(1))).checkInsToMiss.isEmpty())
    }

    @Test
    @DisplayName("A1.2 - the day before yesterday is past grace and becomes missed")
    fun pastGraceIsMissed() {
        val old = checkIn(today.minusDays(2))

        assertEquals(listOf(old), plan(old).checkInsToMiss)
    }

    @Test
    @DisplayName("a check-in that is not due yet is not missed either")
    fun tomorrowIsNotMissed() {
        // The third state, and the only one with no name in the docs: not offered, not missed, not
        // yet due. Nothing generates tomorrow today, so this cannot happen now -- it is pinned here
        // because the alarm horizon is bounded by the generation horizon, and if that ever moves,
        // the first thing to check is that the rollover does not mark the extra day missed the
        // moment it appears. Grace reads "before yesterday", so a future day is safe by
        // construction rather than by a guard someone remembered to write.
        assertTrue(plan(checkIn(today.plusDays(1))).checkInsToMiss.isEmpty())
    }

    @Test
    @DisplayName("the boundary agrees with what the banner still offers")
    fun boundaryMatchesTheOutstandingWindow() {
        // Both sides read Grace. If they ever disagreed, a check-in would fall into the gap --
        // no longer offered to the user and never marked missed -- and response rate would be
        // silently wrong. Asserted here so the two cannot drift apart unnoticed.
        assertEquals(today.minusDays(1), Grace.oldestAnswerableDay(today))
    }

    // ---- What it must not touch ---------------------------------------------------------------

    @Test
    @DisplayName("an answered check-in is never missed, however old")
    fun answeredIsLeftAlone() {
        val answered = checkIn(today.minusDays(30), state = CheckInState.ANSWERED)

        assertTrue(plan(answered).checkInsToMiss.isEmpty())
    }

    @Test
    @DisplayName("a check-in already missed is not rewritten")
    fun alreadyMissedIsSkipped() {
        // Running twice in one day must be a no-op the second time, or every run restamps rows that
        // settled days ago.
        val already = checkIn(today.minusDays(5), state = CheckInState.MISSED)

        assertTrue(plan(already).checkInsToMiss.isEmpty())
    }

    @Test
    @DisplayName("running twice on the same day changes nothing the second time")
    fun idempotentWithinADay() {
        val old = checkIn(today.minusDays(2))
        val first = plan(old).checkInsToMiss

        // The state the first run would have written, fed back in.
        val afterFirst = first.map { it.copy(state = CheckInState.MISSED) }
        val second = RolloverPlanner.plan(today, afterFirst, now = now).checkInsToMiss

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }

    // ---- Catch-up -----------------------------------------------------------------------------

    @Test
    @DisplayName("a run after several days off catches every day at once")
    fun catchesUpAcrossAGap() {
        // The device can be off at 04:00 and no document says what then. Expressing the rule over
        // stored state versus today, rather than over "the day that just ended", is what makes a
        // missed run recoverable instead of a permanent hole in the record.
        val stale = (2L..5L).map { checkIn(today.minusDays(it)) }
        val recent = checkIn(today.minusDays(1))

        val missed = plan(*(stale + recent).toTypedArray()).checkInsToMiss

        assertEquals(stale.toSet(), missed.toSet())
    }

    // ---- O4, the freeze window ----------------------------------------------------------------

    private fun measured(
        state: MeasuredState = MeasuredState.PROVISIONAL,
        syncedAt: Instant? = null,
    ) = MeasuredValue(
        itemId = ItemId("steps"),
        day = today.minusDays(1),
        value = 11_240.0,
        state = state,
        lastSyncedAt = syncedAt,
    )

    private fun freeze(value: MeasuredValue) =
        RolloverPlanner.plan(today, checkIns = emptyList(), measuredValues = listOf(value), now = now)
            .valuesToFreeze

    @Test
    @DisplayName("O4 - a provisional value freezes 24 hours after its read")
    fun freezesAfterTwentyFourHours() {
        val value = measured(syncedAt = now.minusSeconds(24 * 3600))

        assertEquals(listOf(value), freeze(value))
    }

    @Test
    @DisplayName("O4 - a value read less than 24 hours ago stays provisional")
    fun staysProvisionalInsideTheWindow() {
        // The window exists so a late-syncing step count can still correct itself. Freezing early
        // would permanently mis-score the day, which is the outcome O4 was chosen to avoid.
        assertTrue(freeze(measured(syncedAt = now.minusSeconds(23 * 3600))).isEmpty())
    }

    @Test
    @DisplayName("O4 - the clock runs from the sync, not from the day measured")
    fun anchorIsTheSyncNotTheDay() {
        // A value for an old day that only synced an hour ago is young. Anchoring on the day would
        // freeze it immediately and throw away the correction it just received.
        val lateSync = measured(syncedAt = now.minusSeconds(3600)).copy(day = today.minusDays(6))

        assertTrue(freeze(lateSync).isEmpty())
    }

    @Test
    @DisplayName("a value with no recorded read freezes nothing")
    fun nullAnchorFreezesNothing() {
        // The state of the world until M7: nothing populates lastSyncedAt, so the rule ships inert
        // rather than guessing an anchor and freezing values the app has no evidence about.
        assertTrue(freeze(measured(syncedAt = null)).isEmpty())
    }

    @Test
    @DisplayName("an already-frozen value is left alone")
    fun frozenStaysFrozen() {
        val frozen = measured(state = MeasuredState.FROZEN, syncedAt = now.minusSeconds(72 * 3600))

        assertTrue(freeze(frozen).isEmpty())
    }

    @Test
    @DisplayName("a rollover with nothing to do reports so")
    fun emptyPlan() {
        assertTrue(plan(checkIn(today), checkIn(today.minusDays(1))).isEmpty)
    }
}
