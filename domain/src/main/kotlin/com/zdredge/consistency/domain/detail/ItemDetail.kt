package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.GoalResult
import com.zdredge.consistency.domain.scoring.ItemSummary
import com.zdredge.consistency.domain.scoring.PeriodProgress
import com.zdredge.consistency.domain.scoring.RunSummary
import java.time.LocalDate

/**
 * One activity on the *before bed* chart. A night can hold several, which one calendar square cannot
 * show, so each option gets a row (spec §5.4).
 *
 * [flagged] is the option a target forbids — *scrolled on phone*, drawn in red. It is the only red on
 * any chart in the app: a missed day stays grey, by explicit decision.
 */
data class ActivityRow(
    val option: SelectOption,
    val flagged: Boolean,
    val picked: Set<LocalDate>,
)

/**
 * One square on a weekly question's chart: the week, and the single answer that covers it.
 *
 * [closed] rather than a figure, because a weekly question is not rolled up from days — it is one
 * answer, given on the Sunday, and the week it describes closes when that Sunday ends.
 */
data class WeekAnswer(
    val weekStart: LocalDate,
    val cell: DayCell,
    val closed: Boolean,
)

/**
 * The data behind one chart, one variant per [ItemView].
 *
 * The variants exist so `:app` can `when` over them exhaustively and a new view cannot reach the
 * screen without being drawn. `:app` has no tests, so anything it can get wrong silently belongs on
 * this side of the boundary — every variant here carries finished numbers, and the drawing code
 * decides nothing beyond where the ink goes.
 *
 * Days come from [ItemDetail.days] in every case; a variant carries only what is *extra*.
 */
sealed interface Chart {

    /**
     * Times on an axis starting at 04:00, with the rolling average over them.
     *
     * [nights] is already filtered and [trend] is computed over the same nights, as is the typical
     * time in [ItemFigures] — the milestone's exit criterion is that all three agree. The recording
     * count deliberately does not: it ignores the filter, because it measures answering every night.
     */
    data class ClockDots(
        val nights: List<DayCell>,
        val trend: List<TrendPoint>,
        val filter: NightFilter,
    ) : Chart

    /** One row per option, the flagged one last. */
    data class ActivityRows(val rows: List<ActivityRow>) : Chart

    /** A square per day. [weeks] is empty unless the item is targeted by the week (`4/6`). */
    data class DayCalendar(val weeks: List<WeekFigure>) : Chart

    /**
     * A square per day, shaded by amount. [shades] holds a shade for every day that has one.
     *
     * [scale] is **today's**, for the key. Each shade carries the scale of its own day, because a
     * target raised mid-chart moves the bands: two bottles reached a target of two and did not reach
     * a target of three, and shading August by September's target would paint met days as short.
     */
    data class ShadedCalendar(val scale: ShadeScale, val shades: Map<LocalDate, DayShade>) : Chart

    /**
     * A bar per day, with the daily limit as a line. [weeks] is empty unless totals are shown.
     *
     * [dailyTargets] is one span per stretch of days under the same limit, so a limit changed on
     * screen draws as a step rather than as today's value laid over days it never applied to.
     */
    data class DailyBars(val dailyTargets: List<TargetSpan>, val weeks: List<WeekFigure>) : Chart

    /** One square per week, for a question asked once a week. */
    data class WeekSquares(val weeks: List<WeekAnswer>) : Chart

    /** Steps: bars, the daily target as a line, and the two states only a measured day has. */
    data class StepBars(val dailyTargets: List<TargetSpan>, val weeks: List<WeekFigure>) : Chart
}

/**
 * One day's shade: which bucket, on the scale in force that day, and where that sits on today's key.
 *
 * [keyBucket] is what is drawn. A day is placed on the key by **where it stood against its own
 * target** -- at it, one short, well short, above -- not by its raw bucket, because a target of two
 * has three shades and a target of three has four, and the same index means different things on each.
 * Found on the device: shaded by its own scale's colours, a met two-bottle day came out in the colour
 * the key calls "2", below today's target of three, which is the very misreading this was meant to end.
 */
data class DayShade(val bucket: Int, val scale: ShadeScale, val keyBucket: Int = bucket) {
    /** Whether this day's amount reached the target in force that day. */
    val reachesTarget: Boolean get() = scale.buckets[bucket].reachesTarget
}

/** A run of consecutive chart days under the same daily target [value], both ends inclusive. */
data class TargetSpan(val from: LocalDate, val to: LocalDate, val value: Double)

/**
 * The daily target that applied before the one in force now, and the last day it did.
 *
 * Named under the chart only when the change happened inside it -- a key reading "3 target" over a
 * fortnight scored against 2 would otherwise go unexplained.
 */
data class EarlierTarget(val value: Double, val until: LocalDate)

/**
 * One period's figures for one item: how often the target was met, how close on average, and the run.
 *
 * [period] is here because coffee and steps have two sets of these and they must never be merged
 * (spec §3.4, cases 7.1–7.2). A daily cap of 2 and a weekly cap of 14 are different questions with
 * different answers, and an average of the two would be a number describing nothing.
 */
data class GoalFigures(
    val period: Period,
    val summary: ItemSummary,
    val run: RunSummary,
)

/**
 * Everything shown beside an item's chart.
 *
 * **From day one**, unlike the dashboard, which suppresses figures until 14 days exist (§5.5). The
 * user's reasoning, and it overruled the opening position: watching a rate move while it settles is
 * itself worth seeing.
 */
data class ItemFigures(
    /** Spec §5.2 and case 9.8: every figure on the screen covers this same fortnight. */
    val windowDays: List<LocalDate>,
    val daily: GoalFigures?,
    val weekly: GoalFigures?,
    /**
     * For observations, in place of a run: consecutive days the answer was written down.
     *
     * It must be labelled *recorded*, never *streak* — these are the items deliberately never judged,
     * and a streak label would turn the one unscored thing in the app into a performance measure.
     */
    val recording: RunSummary?,
    /** The median time over the shown nights, on the 04:00 axis. Sleep items only. */
    val typicalMinute: Double?,
)

/**
 * One item's detail screen, assembled.
 *
 * [days] is the chart's five weeks — the same cells the table draws, and the same judgements the
 * figures are counted from, so the calendar and the numbers beside it cannot tell different stories.
 * The figures cover the current 14 days -- the dashboard's -- which for a live item are the last 14 of
 * those days. A retired item's chart stops at its retirement and its figures do not, so once it has
 * been retired a fortnight it has nothing left to score (§3.4, 6.2). The runs are computed over the
 * item's whole history, because a run that only looked back a fortnight would reset itself every
 * fortnight.
 *
 * One exception: a **weekly question** has one cell per week rather than per day, and the last of them
 * is the running week's Sunday, which is still ahead of [lastDay]. It is drawn as a week not yet
 * arrived; a chart simply missing its final square would read as a week gone missing.
 */
data class ItemDetail(
    val item: Item,
    /** The version in force on [lastDay] — what the question is *now*, not what it once was. */
    val version: ItemVersion,
    val view: ItemView,
    val chart: Chart,
    val days: List<DayCell>,
    /**
     * Every day from the item's creation to [lastDay] -- the table's rows (spec §5.4). The chart
     * stops at five weeks; the table is the one place older history can be read, so it does not.
     * The last five weeks of it are [days], the same judgements rather than a second pass.
     */
    val log: List<DayCell>,
    val figures: ItemFigures,
    /** The latest day this item can have an answer for: today, yesterday, or its retirement day. */
    val lastDay: LocalDate,
    /** Set when the daily target changed within the chart's days. */
    val earlierTarget: EarlierTarget? = null,
    /**
     * Every **closed** week's result over the item's whole history, keyed by its Monday -- the weekly
     * goal instances the dashboard's trend compares, two weeks against the two before. Empty for an
     * item with no weekly target. Weeks that were not goal instances are absent.
     */
    val weeklyResults: Map<LocalDate, GoalResult> = emptyMap(),
    /** The running week's progress against a weekly count or total, when it has one (§5.3). */
    val openWeek: PeriodProgress? = null,
)
