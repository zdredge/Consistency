package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalResult
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.scoring.ItemLifecycle
import com.zdredge.consistency.domain.scoring.MeasuredScorer
import com.zdredge.consistency.domain.scoring.PeriodProgress
import com.zdredge.consistency.domain.scoring.RollUpCalculator
import com.zdredge.consistency.domain.scoring.TargetResolver
import com.zdredge.consistency.domain.time.DayResolver
import java.time.LocalDate

/**
 * One week of an item, as both a figure to draw and a verdict to count.
 *
 * The same object serves the tally at the end of a calendar row, the total under a week of bars, and
 * the weekly half of the figures — so what the chart says and what the numbers say cannot disagree.
 */
data class WeekFigure(
    val weekStart: LocalDate,
    val active: Boolean,
    /** The count or total. Null when the item declares no roll-up to derive one from. */
    val value: Double?,
    val observedDays: Int,
    /** Days the item was active and that have arrived — not simply seven. */
    val expectedDays: Int,
    /** The target in force on the week's Monday, or null if the goal did not apply this week. */
    val target: Target?,
    val closed: Boolean,
    /** Null when the week is not a goal instance at all. */
    val result: GoalResult?,
    /** Only for an open week with a number to make progress against. */
    val progress: PeriodProgress?,
) {
    /** Spec §3.4: a week with unanswered days is flagged wherever its figure appears. */
    val incomplete: Boolean get() = observedDays < expectedDays
}

/**
 * Builds a week's figure from answers, or from measured values.
 *
 * **A week is closed when today is past its Sunday** — not when seven days have elapsed. The
 * difference is the whole of Sunday, a day on which the week can still be changed, and getting it
 * wrong would score a week while the user could still alter it.
 */
object WeeklyFigures {

    /**
     * A week of an asked item: worked out, stretched, coffee.
     *
     * [aggregation] comes from the item's declared roll-up and nothing else. Spec §3.4 is explicit
     * that roll-ups are declared rather than inferred, so an item with a weekly target and no roll-up
     * produces no figure instead of a guess about whether its days should be counted or summed.
     *
     * The target is resolved on the week's **Monday**. Resolving it on Sunday would make the week a
     * goal week the moment the goal was created midweek, and then judge it on the two days that were
     * left — a guaranteed miss for a week the goal did not apply to.
     */
    fun rollUp(
        item: Item,
        answers: List<Answer>,
        aggregation: RollUpAggregation?,
        targets: TargetResolver,
        weekStart: LocalDate,
        today: LocalDate,
        lastDay: LocalDate,
        weeks: DayResolver,
    ): WeekFigure {
        val days = activeDays(item, weekStart, lastDay, weeks)
        val target = targets.resolve(item.id, Period.WEEK, weekStart)
        val closed = isClosed(weekStart, today, weeks)

        if (aggregation == null) {
            return WeekFigure(
                weekStart = weekStart,
                active = days.isNotEmpty(),
                value = null,
                observedDays = 0,
                expectedDays = days.size,
                target = target,
                closed = closed,
                result = target?.let { GoalResult.excluded(ExclusionReason.NOT_SCORABLE) },
                progress = null,
            )
        }

        val rollUp = RollUpCalculator.weekly(answers.filter { it.day in days }, days, aggregation)
        return WeekFigure(
            weekStart = weekStart,
            active = days.isNotEmpty(),
            value = rollUp.value,
            observedDays = rollUp.observedDays,
            expectedDays = rollUp.expectedDays,
            target = target,
            closed = closed,
            result = target?.let { RollUpCalculator.scoreClosedPeriod(rollUp, it, closed) },
            progress = progress(target, rollUp.value, closed, days.size, weekStart, today, weeks),
        )
    }

    /**
     * A week of steps.
     *
     * Conflicted days are dropped from the total and from the observed count, so a day two sources
     * disagreed about makes the week incomplete rather than quietly inflating it.
     */
    fun measured(
        item: Item,
        values: List<MeasuredValue>,
        targets: TargetResolver,
        weekStart: LocalDate,
        today: LocalDate,
        lastDay: LocalDate,
        weeks: DayResolver,
    ): WeekFigure {
        val days = activeDays(item, weekStart, lastDay, weeks)
        val target = targets.resolve(item.id, Period.WEEK, weekStart)
        val closed = isClosed(weekStart, today, weeks)

        val inWeek = values.filter { it.day in days }
        val usable = inWeek.filter { it.state != MeasuredState.CONFLICTED }
        val total = usable.sumOf { it.value }

        return WeekFigure(
            weekStart = weekStart,
            active = days.isNotEmpty(),
            value = total,
            observedDays = usable.size,
            expectedDays = days.size,
            target = target,
            closed = closed,
            result = target?.let {
                if (closed) MeasuredScorer.scoreWeek(it, inWeek)
                else GoalResult.excluded(ExclusionReason.PERIOD_OPEN)
            },
            progress = progress(target, total, closed, days.size, weekStart, today, weeks),
        )
    }

    /** The days of this week the item was active on and that have arrived. */
    private fun activeDays(item: Item, weekStart: LocalDate, lastDay: LocalDate, weeks: DayResolver): List<LocalDate> =
        generateSequence(weekStart) { it.plusDays(1) }
            .takeWhile { !it.isAfter(weeks.weekEnd(weekStart)) }
            .filter { !it.isAfter(lastDay) && ItemLifecycle.isActiveOn(item, it) }
            .toList()

    private fun isClosed(weekStart: LocalDate, today: LocalDate, weeks: DayResolver): Boolean =
        today.isAfter(weeks.weekEnd(weekStart))

    /** Spec §5.3: an open week shows how far it has got, never a verdict it has not earned. */
    private fun progress(
        target: Target?,
        value: Double,
        closed: Boolean,
        totalDays: Int,
        weekStart: LocalDate,
        today: LocalDate,
        weeks: DayResolver,
    ): PeriodProgress? {
        if (closed || target?.valueNumber == null) return null
        val elapsed = generateSequence(weekStart) { it.plusDays(1) }
            .takeWhile { !it.isAfter(weeks.weekEnd(weekStart)) && !it.isAfter(today) }
            .count()
        return PeriodProgress(
            observed = value,
            target = target.valueNumber,
            elapsedDays = elapsed,
            totalDays = totalDays,
        )
    }
}
