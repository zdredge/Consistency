package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.TEST_ZONE
import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.item
import com.zdredge.consistency.domain.measured
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.scoring.TargetResolver
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.time.DayResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * One week, as the tally at the end of a calendar row and as a verdict in the figures.
 *
 * docs/scoring-cases.md A3 and 9.7 both live here: a week is counted on what was observed, flagged
 * when days are blank, and never scored before it closes.
 */
@DisplayName("A week's figure - scoring-cases A3 and 9.7")
class WeeklyFiguresTest {

    private val weeks = DayResolver(Clock.fixed(Instant.EPOCH, TEST_ZONE))
    private val monday = LocalDate.of(2026, 8, 31)
    private val sunday = monday.plusDays(6)
    private val nextMonday = monday.plusDays(7)

    private val stretched = item("stretched", createdOn = LocalDate.of(2026, 1, 1))
    private val targets = TargetResolver(
        listOf(target("stretched", Direction.AT_LEAST, value = 6.0, period = Period.WEEK)),
    )

    private fun yes(days: Int) = (0 until days).map { answer("stretched", day = monday.plusDays(it.toLong()), bool = true) }

    private fun figure(
        answers: List<com.zdredge.consistency.domain.model.Answer>,
        today: LocalDate = nextMonday,
        resolver: TargetResolver = targets,
        item: com.zdredge.consistency.domain.model.Item = stretched,
    ) = WeeklyFigures.rollUp(
        item = item,
        answers = answers,
        aggregation = RollUpAggregation.COUNT_OF_YES,
        targets = resolver,
        weekStart = monday,
        today = today,
        lastDay = minOf(today, sunday),
        weeks = weeks,
    )

    @Test
    @DisplayName("A3.1 - the count is what was observed, and the week says it was incomplete")
    fun countsObservedOnly() {
        val week = figure(yes(4))

        assertEquals(4.0, week.value)
        assertEquals(4, week.observedDays)
        assertEquals(7, week.expectedDays)
        assertTrue(week.incomplete, "three blank days are not three noes")
    }

    @Test
    @DisplayName("A3.2 - six observed yeses meet a target of six even with a blank day")
    fun observedCountMeetsTheTarget() {
        val week = figure(yes(6))

        assertEquals(GoalOutcome.MET, week.result!!.outcome)
        assertTrue(week.incomplete)
    }

    @Test
    @DisplayName("9.7 - an open week reports progress and is never a miss")
    fun openWeeksAreNotMissed() {
        // Wednesday of the week: two stretches so far against a target of six.
        val wednesday = monday.plusDays(2)
        val week = figure(yes(2), today = wednesday)

        assertEquals(ExclusionReason.PERIOD_OPEN, week.result!!.exclusionReason)
        assertFalse(week.closed)
        assertEquals(2.0, week.progress!!.observed)
        assertEquals(3, week.progress!!.elapsedDays)
    }

    @Test
    @DisplayName("a week is still open on its Sunday and closes the morning after")
    fun sundayIsStillOpen() {
        // The day the old PeriodProgress.closed got wrong. Sunday's answer can still be given.
        assertFalse(figure(yes(6), today = sunday).closed)
        assertTrue(figure(yes(6), today = nextMonday).closed)
    }

    @Test
    @DisplayName("a week the goal did not yet apply to is not a goal week at all")
    fun aWeekBeforeTheTargetExisted() {
        // The real case: every target was created on Thursday 2026-09-10, mid-week. Resolving on the
        // Monday means that week is simply not judged; resolving on the Sunday would judge a six-day
        // target on the two days it existed for, which is a guaranteed miss.
        val createdMidWeek = TargetResolver(
            listOf(target("stretched", Direction.AT_LEAST, value = 6.0, period = Period.WEEK, from = monday.plusDays(3))),
        )
        val week = figure(yes(2), resolver = createdMidWeek)

        assertNull(week.target)
        assertNull(week.result, "not a miss, and not a hit either")
    }

    @Test
    @DisplayName("only the days the item existed for are expected")
    fun aWeekStartingBeforeTheItem() {
        val young = item("stretched", createdOn = monday.plusDays(4))
        val answered = (4..6).map { answer("stretched", day = monday.plusDays(it.toLong()), bool = true) }
        val week = figure(answered, item = young)

        assertEquals(3, week.expectedDays, "four days before it existed are not blanks")
        assertFalse(week.incomplete, "every day it existed for was answered")
        assertEquals(3.0, week.value)
    }

    @Test
    @DisplayName("3.4 - a weekly target with no declared roll-up produces no figure rather than a guess")
    fun noRollUpDeclared() {
        val week = WeeklyFigures.rollUp(
            item = stretched, answers = yes(6), aggregation = null, targets = targets,
            weekStart = monday, today = nextMonday, lastDay = sunday, weeks = weeks,
        )

        assertNull(week.value)
        assertEquals(ExclusionReason.NOT_SCORABLE, week.result!!.exclusionReason)
    }

    // ---- Steps ---------------------------------------------------------------------------------

    private val steps = item("steps", createdOn = LocalDate.of(2026, 1, 1))
    private val stepTargets = TargetResolver(
        listOf(target("steps", Direction.AT_LEAST, value = 56_000.0, period = Period.WEEK)),
    )

    private fun stepWeek(values: List<com.zdredge.consistency.domain.model.MeasuredValue>, today: LocalDate = nextMonday) =
        WeeklyFigures.measured(steps, values, stepTargets, monday, today, minOf(today, sunday), weeks)

    @Test
    @DisplayName("a week of steps sums its days")
    fun stepsSum() {
        val week = stepWeek((0..6).map { measured(day = monday.plusDays(it.toLong()), value = 8_000.0) })

        assertEquals(56_000.0, week.value)
        assertEquals(GoalOutcome.MET, week.result!!.outcome)
        assertFalse(week.incomplete)
    }

    @Test
    @DisplayName("a day two sources reported is left out of the total and makes the week incomplete")
    fun conflictedDaysAreDropped() {
        val week = stepWeek(
            (0..5).map { measured(day = monday.plusDays(it.toLong()), value = 9_000.0) } +
                measured(day = sunday, value = 21_220.0, state = MeasuredState.CONFLICTED),
        )

        assertEquals(54_000.0, week.value, "the conflicted day adds nothing")
        assertTrue(week.incomplete)
        assertEquals(GoalOutcome.MISSED, week.result!!.outcome)
    }

    @Test
    @DisplayName("an open week of steps shows progress, not a verdict")
    fun openStepWeek() {
        val wednesday = monday.plusDays(2)
        val week = stepWeek((0..2).map { measured(day = monday.plusDays(it.toLong()), value = 9_000.0) }, today = wednesday)

        assertEquals(ExclusionReason.PERIOD_OPEN, week.result!!.exclusionReason)
        assertEquals(27_000.0, week.progress!!.observed)
    }
}
