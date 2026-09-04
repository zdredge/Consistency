package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.TEST_ZONE
import com.zdredge.consistency.domain.answeredDay
import com.zdredge.consistency.domain.backfilledCheckIn
import com.zdredge.consistency.domain.checkIn
import com.zdredge.consistency.domain.missedCheckIn
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * docs/scoring-cases.md section 3, plus A1.2 and A1.5.
 *
 * Response rate is the PRIMARY metric (spec 1). Its denominator is every check-in that was expected,
 * which is why those rows exist at all. Crucially it is computed from check-ins alone and never from
 * answers: that is what stops a late answer from repairing a missed check-in.
 */
@DisplayName("Response rate - scoring-cases section 3, A1.2, A1.5")
class ResponseRateTest {

    private val resolver = DayResolver(Clock.fixed(Instant.EPOCH, TEST_ZONE))
    private val day1 = LocalDate.of(2026, 8, 24)

    @Test
    @DisplayName("3.1 - an unanswered check-in adds to the denominator and not the numerator")
    fun missedCheckInCountsAgainstResponseRate() {
        val rate = ResponseRate.of(listOf(checkIn(day1, Slot.MORNING), missedCheckIn(day1)), resolver)
        assertEquals(2, rate.expected)
        assertEquals(1, rate.answered)
        assertEquals(0.5, rate.rate)
    }

    @Test
    @DisplayName("3.2 - a check-in backfilled within grace still counts as answered")
    fun backfilledCountsAsAnswered() {
        val rate = ResponseRate.of(listOf(backfilledCheckIn(day1)), resolver)
        assertEquals(1.0, rate.rate, "backfill inside grace does not cost response rate")
    }

    @Test
    @DisplayName("3.5 - the in-window-only rate is strictly lower when check-ins were backfilled")
    fun inWindowOnlyRateIsStricter() {
        // A week of seven nights, two of them backfilled the following morning.
        val week = (0..6).map { offset ->
            val day = day1.plusDays(offset.toLong())
            if (offset < 2) backfilledCheckIn(day) else checkIn(day)
        }
        val rate = ResponseRate.of(week, resolver)

        assertEquals(1.0, rate.rate, "all seven were answered")
        assertEquals(5.0 / 7.0, rate.inWindowOnlyRate, "only five were answered in window")
        assertTrue(
            rate.inWindowOnlyRate!! < rate.rate!!,
            "the strict figure must remain recoverable and lower",
        )
    }

    @Test
    @DisplayName("A1.2 - a LATE answer does not repair a missed check-in")
    fun lateAnswersDoNotRepairResponseRate() {
        // The check-in of day 1 was never answered; an item was filled in on day 5. Response rate is
        // computed from check-ins alone, so the late answer cannot reach it. Round 1 decision:
        // unlimited backfill must not make the record fiction.
        val rate = ResponseRate.of(listOf(missedCheckIn(day1)), resolver)
        assertEquals(0.0, rate.rate)
        assertEquals(0, rate.answered)
    }

    @Test
    @DisplayName("A1.5 - a whole week filled in late still reports 0% response rate for that week")
    fun aWeekFilledInLateScoresZero() {
        val week = (0..6).flatMap { listOf(missedCheckIn(day1.plusDays(it.toLong()))) }
        assertEquals(0.0, ResponseRate.of(week, resolver).rate)
    }

    @Test
    @DisplayName("response rate is null, not zero, when nothing was expected")
    fun emptyWindowHasNoRate() {
        assertEquals(null, ResponseRate.of(emptyList(), resolver).rate)
    }

    @Test
    @DisplayName("a fully answered fortnight is 100% on both figures")
    fun cleanFortnight() {
        val days = (0..13).flatMap { answeredDay(day1.plusDays(it.toLong())) }
        val rate = ResponseRate.of(days, resolver)
        assertEquals(1.0, rate.rate)
        assertEquals(1.0, rate.inWindowOnlyRate)
    }
}
