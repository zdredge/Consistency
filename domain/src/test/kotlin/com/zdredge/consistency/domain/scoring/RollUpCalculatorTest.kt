package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.TEST_ZONE
import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.time.DayResolver
import com.zdredge.consistency.domain.weekOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * docs/scoring-cases.md A3. Spec 3.4: roll-ups count **observed values only**, and a week containing
 * unanswered days is labelled incomplete wherever the figure appears.
 *
 * Four workouts across a week with two blank days is reported as four, not four-of-five. The blanks
 * might each have been a workout; the app does not guess in either direction, it says the week is
 * incomplete.
 */
@DisplayName("Weekly roll-ups over incomplete weeks - scoring-cases A3")
class RollUpCalculatorTest {

    private val resolver = DayResolver(Clock.fixed(Instant.EPOCH, TEST_ZONE))
    private val monday = LocalDate.of(2026, 8, 24)
    private val week = weekOf(monday, resolver)

    /** Workout answers: [yes] days true, [no] days false, the rest simply absent. */
    private fun workouts(yes: Int, no: Int) =
        (0 until yes).map { answer("workout", day = week[it], bool = true) } +
            (yes until yes + no).map { answer("workout", day = week[it], bool = false) }

    @Test
    @DisplayName("A3.1 - four yes, one no, two unanswered reports 4, not four-of-five")
    fun countsObservedValuesOnly() {
        val rollUp = RollUpCalculator.weekly(workouts(yes = 4, no = 1), week, RollUpAggregation.COUNT_OF_YES)
        assertEquals(4.0, rollUp.value)
        assertEquals(5, rollUp.observedDays)
        assertEquals(7, rollUp.expectedDays)
    }

    @Test
    @DisplayName("A3.2 - a weekly target of AT_LEAST 4 is MET against the observed 4")
    fun theWeeklyTargetIsAssessedAgainstWhatWasObserved() {
        val rollUp = RollUpCalculator.weekly(workouts(yes = 4, no = 1), week, RollUpAggregation.COUNT_OF_YES)
        val outcome = RollUpCalculator.score(
            rollUp,
            target("workout", Direction.AT_LEAST, value = 4.0, period = Period.WEEK),
        )
        assertEquals(GoalOutcome.MET, outcome.outcome)
    }

    @Test
    @DisplayName("A3.3 - the week is flagged incomplete wherever the figure is shown")
    fun incompleteWeeksAreFlagged() {
        val rollUp = RollUpCalculator.weekly(workouts(yes = 4, no = 1), week, RollUpAggregation.COUNT_OF_YES)
        assertTrue(rollUp.incomplete, "two days went unanswered")
    }

    @Test
    @DisplayName("A3.4 - three yes with four unanswered MISSES a target of 4, still flagged incomplete")
    fun theAppGuessesInNeitherDirection() {
        val rollUp = RollUpCalculator.weekly(workouts(yes = 3, no = 0), week, RollUpAggregation.COUNT_OF_YES)
        val outcome = RollUpCalculator.score(
            rollUp,
            target("workout", Direction.AT_LEAST, value = 4.0, period = Period.WEEK),
        )
        assertEquals(GoalOutcome.MISSED, outcome.outcome)
        assertTrue(rollUp.incomplete)
    }

    @Test
    @DisplayName("a fully answered week is not flagged incomplete")
    fun completeWeeksAreNotFlagged() {
        val rollUp = RollUpCalculator.weekly(workouts(yes = 4, no = 3), week, RollUpAggregation.COUNT_OF_YES)
        assertFalse(rollUp.incomplete)
        assertEquals(7, rollUp.observedDays)
    }

    @Test
    @DisplayName("the aggregations sum, average and max each fold the observed values")
    fun otherAggregations() {
        val coffees = listOf(2.0, 3.0, 1.0).mapIndexed { i, n -> answer("coffee", day = week[i], number = n) }
        assertEquals(6.0, RollUpCalculator.weekly(coffees, week, RollUpAggregation.SUM).value)
        assertEquals(2.0, RollUpCalculator.weekly(coffees, week, RollUpAggregation.AVERAGE).value)
        assertEquals(3.0, RollUpCalculator.weekly(coffees, week, RollUpAggregation.MAX).value)
    }
}
