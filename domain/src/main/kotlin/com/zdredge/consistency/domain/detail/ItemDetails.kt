package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.checkin.Answerability
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.scoring.FiguresWindow
import com.zdredge.consistency.domain.scoring.ItemSummary
import com.zdredge.consistency.domain.scoring.RunCalculator
import com.zdredge.consistency.domain.time.DayResolver
import java.time.LocalDate

/**
 * An item's history in; its screen out.
 *
 * **One pass over the days feeds everything.** The chart, the table, the window figures and the runs
 * are all read off the same list of [DayCell]s, so a calendar showing eleven met days and a hit rate
 * computed from nine cannot happen — they are the same nine or eleven judgements counted twice.
 *
 * Changing the night filter re-assembles rather than mutating anything. It is a handful of list
 * operations over five weeks, and it keeps the ViewModel above this as wiring rather than as a second
 * place where the rules live.
 */
object ItemDetails {

    /** Spec §5.4: the chart shows five weeks, because a 14-day calendar is two rows deep. */
    const val CHART_WEEKS: Int = 5

    fun assemble(
        history: ItemHistory,
        today: LocalDate,
        weeks: DayResolver,
        filter: NightFilter = NightFilter.OPENING,
    ): ItemDetail {
        // Which slot the item is asked in is a question about the item *now*: a slot changed
        // yesterday changes which days are answerable today, including days already past.
        val slot = history.latestVersion.slot

        // The window ends on the latest day this item could have an answer for, not on today. A
        // morning item cannot have an answer for tonight, so ending on today would hand it thirteen
        // real days and one guaranteed blank. A retired item stops on the day it was retired.
        val lastDay = minOf(
            Answerability.latestAnswerDay(today, slot),
            history.item.retiredOn ?: LocalDate.MAX,
        )
        val version = history.versionOn(lastDay) ?: history.latestVersion

        val windowDays = FiguresWindow.days(lastDay)
        val chartStart = weeks.weekStart(lastDay).minusWeeks((CHART_WEEKS - 1).toLong())

        // Runs run over the whole history, so the cells start at whichever came first -- the chart's
        // five weeks or the item's creation. Days before it existed are NOT_ACTIVE rather than absent,
        // which is what lets the calendar draw five full weeks for an item that is three days old.
        val cells = DayCells.of(
            history,
            answerDays(minOf(chartStart, history.item.createdOn), lastDay, slot, weeks),
            today,
        )
        val chartCells = cells.filter { !it.day.isBefore(chartStart) }
        val window = windowDays.toSet()
        val windowCells = cells.filter { it.day in window }

        val view = ItemViews.derive(history.item.kind, version, history.targetsOn(lastDay))
        val chartWeekStarts = (0 until CHART_WEEKS).map { chartStart.plusWeeks(it.toLong()) }

        return ItemDetail(
            item = history.item,
            version = version,
            view = view,
            chart = chart(view, history, chartCells, chartWeekStarts, today, lastDay, weeks, filter),
            days = chartCells,
            figures = figures(
                history, version, slot, cells, windowCells, windowDays, today, lastDay, weeks, filter,
            ),
            lastDay = lastDay,
        )
    }

    /**
     * The days this item can have an answer on.
     *
     * For a weekly question that is the **Sundays**: it is answered once, in Sunday night's check-in,
     * so the other six days of the week are not days it declined to answer. The running week's Sunday
     * is included even though it is still ahead — a week with no square at all would read as missing
     * rather than as not yet arrived.
     */
    private fun answerDays(
        from: LocalDate,
        lastDay: LocalDate,
        slot: Slot,
        weeks: DayResolver,
    ): List<LocalDate> {
        if (slot == Slot.WEEKLY) {
            return generateSequence(weeks.weekStart(from)) { it.plusWeeks(1) }
                .takeWhile { !it.isAfter(weeks.weekStart(lastDay)) }
                .map(weeks::weekEnd)
                .toList()
        }
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastDay) }
            .toList()
    }

    private fun chart(
        view: ItemView,
        history: ItemHistory,
        cells: List<DayCell>,
        weekStarts: List<LocalDate>,
        today: LocalDate,
        lastDay: LocalDate,
        weeks: DayResolver,
        filter: NightFilter,
    ): Chart {
        val dailyTarget = history.targetResolver
            .resolve(history.item.id, Period.DAY, lastDay)?.valueNumber
        val weekly = weekFigures(history, weekStarts, today, lastDay, weeks)

        // Exhaustive with no `else`: a view added without a chart to draw it should stop the build.
        return when (view) {
            ItemView.ClockDots -> Chart.ClockDots(
                nights = cells.filter { filter.shows(it.day) },
                // The window reaches back past the chart, so the first drawn night already has six
                // nights of history behind it rather than starting the average from nothing.
                trend = SleepTrend.rollingAverage(
                    history.timesByNight, filter, from = weekStarts.first(), to = lastDay,
                ),
                filter = filter,
            )

            is ItemView.ActivityRows -> Chart.ActivityRows(rows(history, view.flaggedOption, cells))

            is ItemView.DayCalendar ->
                Chart.DayCalendar(if (view.weeklyCount) weekly else emptyList())

            is ItemView.ShadedCalendar -> Chart.ShadedCalendar(
                scale = view.scale,
                shades = cells.mapNotNull { cell ->
                    shadeable(cell)?.let { cell.day to view.scale.bucketOf(it) }
                }.toMap(),
            )

            is ItemView.DailyBars -> Chart.DailyBars(
                dailyTarget = dailyTarget,
                weeks = if (view.weeklyTotals) weekly else emptyList(),
            )

            ItemView.WeekSquares -> Chart.WeekSquares(
                cells.map { WeekAnswer(weeks.weekStart(it.day), it, closed = today.isAfter(it.day)) },
            )

            ItemView.StepBars -> Chart.StepBars(dailyTarget = dailyTarget, weeks = weekly)
        }
    }

    /**
     * The figures beside the chart.
     *
     * Coffee and steps produce both a daily and a weekly set, never merged: a cap of 2 a day and a cap
     * of 14 a week are different questions, and 7.1–7.2 require them reported separately.
     */
    private fun figures(
        history: ItemHistory,
        version: ItemVersion,
        slot: Slot,
        cells: List<DayCell>,
        windowCells: List<DayCell>,
        windowDays: List<LocalDate>,
        today: LocalDate,
        lastDay: LocalDate,
        weeks: DayResolver,
        filter: NightFilter,
    ): ItemFigures {
        val observation = version.classification == Classification.OBSERVATION

        val daily = if (slot != Slot.WEEKLY && history.hasTargetIn(Period.DAY)) {
            GoalFigures(
                period = Period.DAY,
                summary = ItemSummary.of(windowCells.mapNotNull { it.result }),
                run = RunCalculator.itemRun(
                    cells.mapNotNull { cell -> cell.result?.let { cell.day to it.outcome } }.toMap(),
                    upTo = lastDay,
                ),
            )
        } else {
            null
        }

        return ItemFigures(
            windowDays = windowDays,
            daily = daily,
            weekly = weeklyFigures(history, slot, cells, windowDays, today, lastDay, weeks),
            // A goal shows its run; an observation has no goal to have met, so it shows how
            // consistently it was written down instead -- and that count ignores the night filter,
            // because it measures answering every night rather than the nights on screen.
            recording = if (observation) {
                RecordingCount.of(history.item, slot, history.answers, today, lastDay)
            } else {
                null
            },
            typicalMinute = if (version.answerType == AnswerType.TIME) {
                SleepTrend.typicalMinute(
                    history.timesByNight, filter, from = windowDays.first(), to = lastDay,
                )
            } else {
                null
            },
        )
    }

    /**
     * The weekly half of the figures.
     *
     * Two shapes, and they are genuinely different. A weekly **question** is one answer given on the
     * Sunday, judged against the target in force that day. A weekly **count or total** is derived from
     * the days beneath it and judged against the target in force on the week's **Monday** — resolving
     * it on the Sunday would make a goal created midweek apply to the whole week and then judge it on
     * the two days that were left, which is a guaranteed miss for a week the goal did not cover.
     *
     * Either way only **closed** weeks count (§5.3, case 11.4): a week in flight is progress, and the
     * running week is always in flight.
     */
    private fun weeklyFigures(
        history: ItemHistory,
        slot: Slot,
        cells: List<DayCell>,
        windowDays: List<LocalDate>,
        today: LocalDate,
        lastDay: LocalDate,
        weeks: DayResolver,
    ): GoalFigures? {
        if (!history.hasTargetIn(Period.WEEK)) return null

        if (slot == Slot.WEEKLY) {
            val closed = cells.filter { today.isAfter(it.day) }
            val window = windowDays.toSet()
            return GoalFigures(
                period = Period.WEEK,
                summary = ItemSummary.of(closed.filter { it.day in window }.mapNotNull { it.result }),
                run = RunCalculator.itemRun(
                    closed.mapNotNull { cell ->
                        cell.result?.let { weeks.weekStart(cell.day) to it.outcome }
                    }.toMap(),
                    upTo = lastDay,
                ),
            )
        }

        val windowWeeks = FiguresWindow.closedWeekStarts(windowDays, today, weeks)
        val allWeeks = generateSequence(weeks.weekStart(history.item.createdOn)) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(weeks.weekStart(lastDay)) }
            .filter { today.isAfter(weeks.weekEnd(it)) }
            .toList()

        return GoalFigures(
            period = Period.WEEK,
            summary = ItemSummary.of(
                weekFigures(history, windowWeeks, today, lastDay, weeks).mapNotNull { it.result },
            ),
            // Keyed by Monday, over the weeks that had a target. A week before the goal existed
            // carries no result, so it neither extends the run nor breaks it -- which matters here,
            // because every item's first week is exactly that.
            run = RunCalculator.itemRun(
                weekFigures(history, allWeeks, today, lastDay, weeks)
                    .mapNotNull { week -> week.result?.let { week.weekStart to it.outcome } }
                    .toMap(),
                upTo = lastDay,
            ),
        )
    }

    private fun weekFigures(
        history: ItemHistory,
        weekStarts: List<LocalDate>,
        today: LocalDate,
        lastDay: LocalDate,
        weeks: DayResolver,
    ): List<WeekFigure> = weekStarts.map { weekStart ->
        if (history.item.kind == ItemKind.MEASURED) {
            WeeklyFigures.measured(
                history.item, history.measured, history.targetResolver,
                weekStart, today, lastDay, weeks,
            )
        } else {
            WeeklyFigures.rollUp(
                history.item, history.answers, history.rollUp?.aggregation, history.targetResolver,
                weekStart, today, lastDay, weeks,
            )
        }
    }

    /**
     * One row per option, **the flagged one last**, which is where the mockups put it: the activities
     * sit together in blue and the one the target forbids is separated out beneath them in red.
     */
    private fun rows(
        history: ItemHistory,
        flagged: OptionId?,
        cells: List<DayCell>,
    ): List<ActivityRow> = history.options
        .sortedWith(compareBy({ it.id == flagged }, { it.ordinal }))
        .map { option ->
            ActivityRow(
                option = option,
                flagged = option.id == flagged,
                picked = cells.mapNotNull { cell ->
                    cell.day.takeIf { (cell.value as? DayValue.Choices)?.options?.contains(option.id) == true }
                }.toSet(),
            )
        }

    /** The number a shade is chosen from: an amount, or a point on the 1–5 scale. */
    private fun shadeable(cell: DayCell): Double? = when (val value = cell.value) {
        is DayValue.Amount -> value.value
        is DayValue.Rating -> value.value.toDouble()
        else -> null
    }
}
