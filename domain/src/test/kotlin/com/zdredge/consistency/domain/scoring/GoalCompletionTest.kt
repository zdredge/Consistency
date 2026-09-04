package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.TEST_ZONE
import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.GoalResult
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.time.DayResolver
import com.zdredge.consistency.domain.weekOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * docs/scoring-cases.md section 11 and 9.7 -- the O1 resolution.
 *
 * Goal completion is **two figures, one per granularity, never merged** with each other or with
 * response rate (constraint 8). Pooling daily and weekly goals into one ratio would silently drown
 * the ~2 weekly instances per window under the ~14 daily ones, a hidden weighting that also makes
 * the number untraceable to any behaviour.
 */
@DisplayName("Goal-completion rings - scoring-cases section 11 and 9.7")
class GoalCompletionTest {

    private val resolver = DayResolver(Clock.fixed(Instant.EPOCH, TEST_ZONE))
    private val monday = LocalDate.of(2026, 8, 24)

    private fun results(met: Int, missed: Int, excluded: Int = 0): List<GoalResult> =
        List(met) { GoalResult(GoalOutcome.MET) } +
            List(missed) { GoalResult(GoalOutcome.MISSED) } +
            List(excluded) { GoalResult.excluded(ExclusionReason.NO_ANSWER) }

    @Test
    @DisplayName("11.1 - 42 of 56 daily instances met is a daily ring of 75%")
    fun dailyRing() {
        // 4 daily goals across 14 answered days.
        val completion = GoalCompletion.of(results(met = 42, missed = 14))
        assertEquals(56, completion.scored)
        assertEquals(0.75, completion.ratio)
    }

    @Test
    @DisplayName("11.2 - no-opportunity instances leave both numerator and denominator alone")
    fun noOpportunityIsExcludedFromTheRing() {
        val withNeutral = results(met = 42, missed = 14) +
            List(3) { GoalResult.excluded(ExclusionReason.NO_OPPORTUNITY) }
        val completion = GoalCompletion.of(withNeutral)

        assertEquals(56, completion.scored, "the three neutral answers are not scored instances")
        assertEquals(0.75, completion.ratio, "and so cannot move the ring in either direction")
    }

    @Test
    @DisplayName("11.3 - 10 of 14 weekly instances met is a weekly ring of about 71%")
    fun weeklyRing() {
        // 7 weekly goals across 2 closed weeks.
        val completion = GoalCompletion.of(results(met = 10, missed = 4))
        assertEquals(14, completion.scored)
        assertEquals(10.0 / 14.0, completion.ratio)
    }

    @Test
    @DisplayName("11.4 - an unclosed week is excluded from the weekly ring, never counted as missed")
    fun openWeeksAreExcluded() {
        // Spec 5.3: a week is only marked met or missed once it closes. Scoring a Tuesday against a
        // seven-day target makes every week look like failure until Sunday.
        val week = weekOf(monday, resolver)
        val soFar = listOf(10_000.0, 10_000.0, 10_000.0)
            .mapIndexed { i, n -> answer("steps", day = week[i], number = n) }
        val rollUp = RollUpCalculator.weekly(soFar, week, RollUpAggregation.SUM)
        val weeklyTarget = target("steps", Direction.AT_LEAST, value = 70_000.0, period = Period.WEEK)

        val result = RollUpCalculator.scoreClosedPeriod(rollUp, weeklyTarget, periodClosed = false)

        assertEquals(GoalOutcome.EXCLUDED, result.outcome)
        assertEquals(ExclusionReason.PERIOD_OPEN, result.exclusionReason)
        assertNotEquals(GoalOutcome.MISSED, result.outcome, "30,000 of 70,000 mid-week is not a failure")
    }

    @Test
    @DisplayName("9.7 - mid-week a weekly target reports progress against elapsed days")
    fun midWeekShowsProgress() {
        // Wednesday of a Monday-start week: 30,000 steps over three elapsed days, target 70,000.
        val progress = PeriodProgress(observed = 30_000.0, target = 70_000.0, elapsedDays = 3, totalDays = 7)

        assertEquals(3, progress.elapsedDays)
        assertTrue(!progress.closed, "the week is still open")
        assertEquals(30_000.0 / 70_000.0, progress.fractionOfTarget)
    }

    @Test
    @DisplayName("11.7 - a goal with no scored instances drops out of the denominator entirely")
    fun goalsWithNothingScoredDoNotDilute() {
        // All no-opportunity, or newly created: it must not drag the ring down, and with nothing at
        // all scored the ring itself is null rather than zero.
        val completion = GoalCompletion.of(List(14) { GoalResult.excluded(ExclusionReason.NO_OPPORTUNITY) })
        assertEquals(0, completion.scored)
        assertNull(completion.ratio, "nothing to report is not the same as nothing achieved")
    }

    @Test
    @DisplayName("the daily and weekly rings are kept distinct and are never combined")
    fun theTwoRingsAreNeverMerged() {
        // Constraint 8 and the O1 resolution. The rings answer different questions, and a blended
        // figure could not distinguish daily follow-through from weekly.
        val rings = GoalCompletionRings(
            daily = GoalCompletion.of(results(met = 42, missed = 14)),
            weekly = GoalCompletion.of(results(met = 10, missed = 4)),
        )
        assertEquals(0.75, rings.daily.ratio)
        assertEquals(10.0 / 14.0, rings.weekly.ratio)
        assertNotEquals(rings.daily.ratio, rings.weekly.ratio)

        // There is deliberately no combined() on this type. If one ever appears, constraint 8 has
        // been broken.
        assertTrue(
            GoalCompletionRings::class.java.methods.none { it.name.contains("combined", ignoreCase = true) },
            "no API may offer a single blended goal-completion figure",
        )
    }
}
