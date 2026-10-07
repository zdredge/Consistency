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

    /** Aggregations that describe the values seen rather than accumulate them. */
    private val OF_VALUES = setOf(RollUpAggregation.AVERAGE, RollUpAggregation.MAX)

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
            // A count or a total of nothing is zero; an average or a maximum of nothing is not a
            // number at all. A silent week of mindset reading "0" would claim the worst week possible.
            value = if (rollUp.observedDays == 0 && aggregation in OF_VALUES) null else rollUp.value,
            observedDays = rollUp.observedDays,
            expectedDays = rollUp.expectedDays,
            target = target,
            closed = closed,
            result = target?.let { RollUpCalculator.scoreClosedPeriod(rollUp, it, closed) },
            progress = progress(target, rollUp.value, closed, item, weekStart, today, weeks),
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
            progress = progress(target, total, closed, item, weekStart, today, weeks),
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

    /**
     * Spec §5.3: an open week shows how far it has got, never a verdict it has not earned.
     *
     * **Both counts are over the days of the week the item exists on**, including the ones still
     * ahead. [activeDays] stops at the last answerable day, which is right for what was observed and
     * wrong here: as the total it made every running week end today, and the dashboard read "with 0
     * days to go" on a Wednesday. And elapsed days count from the item's first day in the week, not
     * from Monday, or a goal created midweek would have fewer days to go than it really has.
     */
    private fun progress(
        target: Target?,
        value: Double,
        closed: Boolean,
        item: Item,
        weekStart: LocalDate,
        today: LocalDate,
        weeks: DayResolver,
    ): PeriodProgress? {
        if (closed || target?.valueNumber == null) return null
        val weekDays = generateSequence(weekStart) { it.plusDays(1) }
            .takeWhile { !it.isAfter(weeks.weekEnd(weekStart)) }
            .filter { ItemLifecycle.isActiveOn(item, it) }
            .toList()
        return PeriodProgress(
            observed = value,
            target = target.valueNumber,
            elapsedDays = weekDays.count { !it.isAfter(today) },
            totalDays = weekDays.size,
        )
    }
}
