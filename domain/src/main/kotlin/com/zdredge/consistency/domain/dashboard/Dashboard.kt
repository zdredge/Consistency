package com.zdredge.consistency.domain.dashboard

import com.zdredge.consistency.domain.detail.DayState
import com.zdredge.consistency.domain.detail.DayValue
import com.zdredge.consistency.domain.detail.GoalLine
import com.zdredge.consistency.domain.detail.ItemDetail
import com.zdredge.consistency.domain.detail.ItemDetails
import com.zdredge.consistency.domain.detail.ItemHistory
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.GoalResult
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.scoring.FiguresWindow
import com.zdredge.consistency.domain.scoring.GoalCompletion
import com.zdredge.consistency.domain.scoring.GoalCompletionRings
import com.zdredge.consistency.domain.scoring.ItemSummary
import com.zdredge.consistency.domain.scoring.PeriodProgress
import com.zdredge.consistency.domain.scoring.ResponseRate
import com.zdredge.consistency.domain.scoring.RunCalculator
import com.zdredge.consistency.domain.time.DayResolver
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Which way a goal is heading, fortnight against fortnight (the M10 review replaced §5.1's levels). */
enum class Trend { SLIPPING, HOLDING, IMPROVING }

/** Met out of scored, for one period. Exclusions are in neither. */
data class Tally(val met: Int, val scored: Int) {
    val hitRate: Double? get() = if (scored == 0) null else met.toDouble() / scored

    companion object {
        fun of(results: Collection<GoalResult>) = Tally(
            met = results.count { it.outcome == GoalOutcome.MET },
            scored = results.count { it.outcome == GoalOutcome.MET || it.outcome == GoalOutcome.MISSED },
        )
    }
}

/**
 * One goal on the trend carousel: its question, its goal in words, and the two periods compared.
 *
 * [unit] is what a period is called -- days, nights or weeks -- so "Met on 5 of the last 14 days"
 * never says days of a question about nights.
 */
data class TrendGoal(
    val itemId: ItemId,
    val question: String,
    val goal: String,
    val period: Period,
    val unit: String,
    val before: Tally,
    val now: Tally,
    val trend: Trend,
    /** For an at-least number goal: the average on the days it was missed, in the window. */
    val missedAverage: Double?,
    val unitLabel: String?,
    /** For a weekly count or total: the running week so far (§5.3, case 9.7). */
    val openWeek: PeriodProgress?,
)

/** The score card: three separate figures and the run. Never combined (constraint 8, case 11.6). */
data class ScoreFigures(
    val responseRate: ResponseRate,
    val rings: GoalCompletionRings,
    val longestRun: Int,
)

/**
 * Everything the dashboard shows, assembled.
 *
 * [score] is null for the first 13 days and [trends] for the first 27 (§5.5, and the M10 review: a
 * trend needs a fortnight to compare against). Before then the screen shows how far along it is.
 */
data class DashboardFigures(
    val today: LocalDate,
    /** Days since install, today included. Zero before anything has been generated. */
    val daysOfHistory: Int,
    /** Closed check-ins answered, and closed check-ins in all -- the first-run count. */
    val answeredSoFar: Int,
    val closedSoFar: Int,
    val scoreFrom: LocalDate?,
    val trendsFrom: LocalDate?,
    val score: ScoreFigures?,
    /** Every trend with at least one goal, in carousel order: slipping, holding, improving. */
    val trends: Map<Trend, List<TrendGoal>>?,
)

/**
 * The dashboard, from every item's history and every check-in.
 *
 * **Every figure goes through `ItemDetails.assemble`**, the same assembly the item screens use, so a
 * goal cannot read one way on the dashboard and another when opened. What is new here is only the
 * summing across items, the closed-check-in rule for response rate, and the trend.
 */
object Dashboard {

    /** §5.5: figures appear once 14 days of history exist. */
    const val SCORE_AFTER_DAYS: Int = FiguresWindow.DAYS

    /** A trend compares two fortnights, so it needs 28 days. */
    const val TRENDS_AFTER_DAYS: Int = 2 * FiguresWindow.DAYS

    /** Ten points either way, as a fraction. */
    const val TREND_THRESHOLD: Double = 0.10

    /** Closed weeks per side of a weekly goal's comparison. */
    private const val WEEKS_COMPARED = 2

    fun assemble(
        histories: List<ItemHistory>,
        checkIns: List<CheckIn>,
        today: LocalDate,
        weeks: DayResolver,
    ): DashboardFigures {
        val installDay = checkIns.minOfOrNull { it.day }
        val days = installDay?.let { ChronoUnit.DAYS.between(it, today).toInt() + 1 } ?: 0
        val closed = checkIns.filter { it.isClosed() }

        val details by lazy {
            histories
                .filter { it.latestVersion.classification == Classification.GOAL }
                .map { it to ItemDetails.assemble(it, today, weeks) }
        }

        return DashboardFigures(
            today = today,
            daysOfHistory = days,
            answeredSoFar = closed.count { it.state == CheckInState.ANSWERED },
            closedSoFar = closed.size,
            scoreFrom = installDay?.plusDays(SCORE_AFTER_DAYS - 1L),
            trendsFrom = installDay?.plusDays(TRENDS_AFTER_DAYS - 1L),
            score = if (days >= SCORE_AFTER_DAYS) score(checkIns, today, weeks, details.map { it.second }) else null,
            trends = if (days >= TRENDS_AFTER_DAYS) trends(details, today, weeks) else null,
        )
    }

    /**
     * Decided in the M10 plan: only a check-in that can no longer change counts toward response rate.
     * One still inside its grace is neither answered nor missed yet, so the ring does not dip every
     * morning and recover every night -- the same rule §5.3 applies to an open week.
     */
    private fun CheckIn.isClosed(): Boolean = state == CheckInState.ANSWERED || state == CheckInState.MISSED

    private fun score(
        checkIns: List<CheckIn>,
        today: LocalDate,
        weeks: DayResolver,
        details: List<ItemDetail>,
    ): ScoreFigures {
        val window = FiguresWindow.days(today).toSet()
        return ScoreFigures(
            responseRate = ResponseRate.of(checkIns.filter { it.day in window && it.isClosed() }, weeks),
            // Instance-based within each granularity and never pooled (cases 11.1-11.6). Each item's
            // own figures already cover the one window and only closed weeks.
            rings = GoalCompletionRings(
                daily = completion(details.mapNotNull { it.figures.daily?.summary }),
                weekly = completion(details.mapNotNull { it.figures.weekly?.summary }),
            ),
            longestRun = RunCalculator.globalRun(checkIns, upTo = today).longest,
        )
    }

    private fun completion(summaries: List<ItemSummary>) = GoalCompletion(
        met = summaries.sumOf { it.met },
        missed = summaries.sumOf { it.missed },
        excluded = summaries.sumOf { it.excluded },
    )

    private fun trends(
        details: List<Pair<ItemHistory, ItemDetail>>,
        today: LocalDate,
        weeks: DayResolver,
    ): Map<Trend, List<TrendGoal>> {
        val goals = details.mapNotNull { (history, detail) -> trendOf(history, detail, today, weeks) }
        return Trend.entries
            .associateWith { trend -> goals.filter { it.trend == trend }.sortedBy { order(it) } }
            .filterValues { it.isNotEmpty() }
    }

    /** Biggest movement first: the worst drop leads Slipping, the biggest gain leads Improving. */
    private fun order(goal: TrendGoal): Double {
        val change = (goal.now.hitRate ?: 0.0) - (goal.before.hitRate ?: 0.0)
        return if (goal.trend == Trend.IMPROVING) -change else change
    }

    /**
     * One goal's trend, or null when it has nothing to compare: no scored instances now, or none in
     * the period before (a goal created three weeks ago has no earlier fortnight).
     *
     * A goal with a daily target is judged on its daily instances -- coffee and steps included, by the
     * user's decision -- and a weekly-only goal on its last two closed weeks against the two before.
     */
    fun trendOf(history: ItemHistory, detail: ItemDetail, today: LocalDate, weeks: DayResolver): TrendGoal? {
        val daily = detail.figures.daily != null
        val (before, now) = if (daily) dailyTallies(detail) else weeklyTallies(detail, today, weeks)
        val nowRate = now.hitRate ?: return null
        val beforeRate = before.hitRate ?: return null

        val period = if (daily) Period.DAY else Period.WEEK
        val target = history.targetResolver.resolve(history.item.id, period, detail.lastDay) ?: return null
        val change = nowRate - beforeRate
        val tolerance = 1e-9

        return TrendGoal(
            itemId = history.item.id,
            question = GoalLine.question(detail.version.prompt),
            goal = GoalLine.of(target, detail.version, history.options, history.rollUp),
            period = period,
            unit = when {
                !daily -> "weeks"
                detail.version.slot == Slot.MORNING -> "nights"
                else -> "days"
            },
            before = before,
            now = now,
            trend = when {
                change <= -TREND_THRESHOLD + tolerance -> Trend.SLIPPING
                change >= TREND_THRESHOLD - tolerance -> Trend.IMPROVING
                else -> Trend.HOLDING
            },
            missedAverage = if (daily && target.direction == Direction.AT_LEAST) missedAverage(detail) else null,
            unitLabel = detail.version.unitLabel?.lowercase(),
            openWeek = if (daily) null else detail.openWeek,
        )
    }

    /** The window's 14 answer days against the 14 before them, read off the same cells. */
    private fun dailyTallies(detail: ItemDetail): Pair<Tally, Tally> {
        val now = detail.figures.windowDays.toSet()
        val start = detail.figures.windowDays.first()
        val before = (1..FiguresWindow.DAYS).map { start.minusDays(it.toLong()) }.toSet()
        val results = detail.log.mapNotNull { cell -> cell.result?.let { cell.day to it } }
        return Tally.of(results.filter { it.first in before }.map { it.second }) to
            Tally.of(results.filter { it.first in now }.map { it.second })
    }

    /**
     * The two most recently closed weeks against the two before them, **by date**.
     *
     * Keyed on the calendar rather than on whichever results exist: a retired goal's results stop at
     * its retirement, and taking its last two would compare weeks from months ago as if they were
     * this fortnight's (spec §3.4, case 6.2). A week with no result is simply absent from its tally.
     */
    private fun weeklyTallies(detail: ItemDetail, today: LocalDate, weeks: DayResolver): Pair<Tally, Tally> {
        // The running week is never closed, so the latest closed week is always the one before it.
        val latestClosed = weeks.weekStart(today).minusWeeks(1)
        fun tally(newest: LocalDate) = Tally.of(
            (0 until WEEKS_COMPARED).mapNotNull { detail.weeklyResults[newest.minusWeeks(it.toLong())] },
        )
        return tally(latestClosed.minusWeeks(WEEKS_COMPARED.toLong())) to tally(latestClosed)
    }

    /** "On the days you missed, you averaged 1.5 bottles." Null when nothing was missed. */
    private fun missedAverage(detail: ItemDetail): Double? {
        val window = detail.figures.windowDays.toSet()
        val amounts = detail.log
            .filter { it.day in window && it.state == DayState.MISSED }
            .mapNotNull { (it.value as? DayValue.Amount)?.value }
        return if (amounts.isEmpty()) null else amounts.average()
    }
}
