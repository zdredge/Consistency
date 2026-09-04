package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.answeredDay
import com.zdredge.consistency.domain.checkIn
import com.zdredge.consistency.domain.missedCheckIn
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * docs/scoring-cases.md section 4, plus A1.3.
 *
 * Spec 3.5: the run counts consecutive days on which every scheduled check-in was ANSWERED. It
 * measures showing up, not performing. An all-goals-met run would sit near zero permanently with ten
 * goals, and under that definition the incentive on a bad day is to not open the app. Under this one
 * the bad day and the honest answer are the same action.
 */
@DisplayName("Runs - scoring-cases section 4, A1.3")
class RunCalculatorTest {

    private val day1 = LocalDate.of(2026, 8, 24)
    private fun day(n: Int) = day1.plusDays(n.toLong() - 1)

    @Test
    @DisplayName("4.1 - five days with every check-in answered is a current run of 5")
    fun fiveCleanDays() {
        val checkIns = (1..5).flatMap { answeredDay(day(it)) }
        val run = RunCalculator.globalRun(checkIns, upTo = day(5))
        assertEquals(5, run.current)
        assertEquals(5, run.longest)
    }

    @Test
    @DisplayName("4.2 - a missed night resets the current run; the longest never decreases")
    fun aMissResetsCurrentButNotLongest() {
        val checkIns = (1..5).flatMap { answeredDay(day(it)) } +
            listOf(checkIn(day(6), Slot.MORNING), missedCheckIn(day(6)))
        val run = RunCalculator.globalRun(checkIns, upTo = day(6))
        assertEquals(0, run.current)
        assertEquals(5, run.longest, "the longest run is a historical fact that only ever increases")
    }

    @Test
    @DisplayName("4.3 - a check-in backfilled within grace preserves the run")
    fun backfillWithinGracePreservesTheRun() {
        // The record stays flagged as backfilled so the strict figure remains recoverable, but the
        // run is about showing up, and the user did show up.
        val checkIns = (1..5).flatMap { answeredDay(day(it)) } +
            listOf(checkIn(day(6), Slot.MORNING), com.zdredge.consistency.domain.backfilledCheckIn(day(6)))
        val run = RunCalculator.globalRun(checkIns, upTo = day(6))
        assertEquals(6, run.current)
    }

    @Test
    @DisplayName("4.4 - a day where every check-in was answered and every goal failed continues the run")
    fun answeringWhileFailingEverythingKeepsTheRun() {
        // The entire point of the definition. The run must reward honesty on a bad day, not punish it.
        val checkIns = (1..7).flatMap { answeredDay(day(it)) }
        val run = RunCalculator.globalRun(checkIns, upTo = day(7))
        assertEquals(7, run.current, "goal outcomes are not an input to the global run at all")
    }

    @Test
    @DisplayName("A1.3 - a LATE answer does not restore a broken run")
    fun lateAnswersDoNotRestoreTheRun() {
        // Day 1's check-in was missed and later filled in. The check-in stays MISSED, so the run
        // still breaks there: the data counts, the metric does not.
        val checkIns = listOf(missedCheckIn(day(1)), checkIn(day(1), Slot.MORNING)) +
            (2..5).flatMap { answeredDay(day(it)) }
        val run = RunCalculator.globalRun(checkIns, upTo = day(5))
        assertEquals(4, run.current, "the run restarts after the break, it is not healed")
        assertEquals(4, run.longest)
    }

    @Test
    @DisplayName("4.5 - a per-item run is independent of the global run")
    fun perItemRun() {
        val outcomes = (1..12).associate { day(it) to GoalOutcome.MET } +
            mapOf(day(13) to GoalOutcome.MISSED)
        val run = RunCalculator.itemRun(outcomes, upTo = day(13))
        assertEquals(0, run.current)
        assertEquals(12, run.longest)
    }

    @Test
    @DisplayName("an EXCLUDED day neither extends nor breaks a per-item run")
    fun exclusionsAreNeutralForItemRuns() {
        // Spec 3.5, added with the no-opportunity rule: a neutral answer is not a performance, so it
        // cannot extend the run, and it is not a failure, so it cannot break it.
        val outcomes = mapOf(
            day(1) to GoalOutcome.MET,
            day(2) to GoalOutcome.EXCLUDED,
            day(3) to GoalOutcome.MET,
        )
        val run = RunCalculator.itemRun(outcomes, upTo = day(3))
        assertEquals(2, run.current, "two met days, with the neutral day skipped over")
    }
}
