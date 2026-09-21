package com.zdredge.consistency.ui.home

import com.zdredge.consistency.domain.dashboard.DashboardFigures
import com.zdredge.consistency.domain.dashboard.Tally
import com.zdredge.consistency.domain.dashboard.Trend
import com.zdredge.consistency.domain.dashboard.TrendGoal
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.scoring.GoalCompletion
import com.zdredge.consistency.ui.checkin.asAnswer
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One ring and its legend row. [fraction] is null when nothing has been scored yet. */
data class RingUi(val name: String, val fraction: Double?, val value: String, val detail: String)

data class ScoreUi(val rings: List<RingUi>, val longestRun: String)

/** Before day 14: nothing is scored yet, and the screen says how far along it is. */
data class FirstRunUi(val title: String, val answered: String, val progress: Float, val detail: String)

/** Days 14-27: the rings are shown and the trends wait. */
data class TrendsWaitingUi(val title: String, val progress: Float, val detail: String)

/** One goal on the carousel, exactly as the M10 review drew it. */
data class GoalCardUi(
    val question: String,
    val goal: String,
    val before: String,
    val beforeFraction: Float,
    val now: String,
    val nowFraction: Float,
    val met: String,
    val extra: String?,
)

data class TrendPageUi(val title: String, val subtitle: String, val goals: List<GoalCardUi>)

data class DashboardUi(
    val firstRun: FirstRunUi? = null,
    val score: ScoreUi? = null,
    val trendsWaiting: TrendsWaitingUi? = null,
    val trends: List<TrendPageUi> = emptyList(),
)

private val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")

/**
 * Dashboard figures into the strings the screen draws.
 *
 * A function, as `presentItemDetail` is, so a preview can run the real pipeline: an invented history
 * through `Dashboard.assemble` and then through this.
 */
internal fun presentDashboard(figures: DashboardFigures): DashboardUi {
    val score = figures.score
    if (score == null) {
        return DashboardUi(
            firstRun = FirstRunUi(
                title = "Day ${figures.daysOfHistory} of 14",
                answered = "You've answered ${figures.answeredSoFar} of ${figures.closedSoFar} check-ins so far.",
                progress = (figures.daysOfHistory / 14f).coerceIn(0f, 1f),
                detail = "Your figures appear after two weeks" +
                    (figures.scoreFrom?.let { ", on ${it.format(longDate)}" } ?: "") +
                    ". Until then there are too few days for a percentage to mean anything.",
            ),
        )
    }

    val rate = score.responseRate
    return DashboardUi(
        score = ScoreUi(
            rings = listOf(
                RingUi(
                    name = "Response rate",
                    fraction = rate.rate,
                    value = rate.rate?.let(::percent) ?: "—",
                    detail = "${rate.answered} of ${rate.expected} check-ins",
                ),
                ring("Daily goals", score.rings.daily, pendingDetail = "Nothing scored yet"),
                ring("Weekly goals", score.rings.weekly, pendingDetail = "No week has closed yet", unit = " weeks"),
            ),
            longestRun = "${score.longestRun} days",
        ),
        trendsWaiting = if (figures.trends == null) {
            TrendsWaitingUi(
                title = "Day ${figures.daysOfHistory} of 28",
                progress = (figures.daysOfHistory / 28f).coerceIn(0f, 1f),
                detail = "A trend compares a fortnight with the one before, so the Slipping, Holding and " +
                    "Improving panels appear after four weeks" +
                    (figures.trendsFrom?.let { ", on ${it.format(longDate)}" } ?: "") + ".",
            )
        } else {
            null
        },
        trends = figures.trends.orEmpty().map { (trend, goals) ->
            TrendPageUi(
                title = when (trend) {
                    Trend.SLIPPING -> "Slipping"
                    Trend.HOLDING -> "Holding"
                    Trend.IMPROVING -> "Improving"
                },
                subtitle = when (trend) {
                    Trend.SLIPPING -> "Worse than the fortnight before"
                    Trend.HOLDING -> "About the same as the fortnight before"
                    Trend.IMPROVING -> "Better than the fortnight before"
                },
                goals = goals.map(::goalCard),
            )
        },
    )
}

/**
 * A goal ring's legend row. Pending, never 0%, when nothing in it has been scored (case 11.5) -- a
 * ring reading 0% on the day no week has closed would report a failure that has not happened.
 */
private fun ring(name: String, completion: GoalCompletion, pendingDetail: String, unit: String = ""): RingUi {
    val ratio = completion.ratio
    return RingUi(
        name = name,
        fraction = ratio,
        value = ratio?.let(::percent) ?: "Pending",
        detail = if (ratio == null) pendingDetail else "${completion.met} of ${completion.scored}$unit",
    )
}

private fun goalCard(goal: TrendGoal): GoalCardUi = GoalCardUi(
    question = goal.question,
    goal = "Goal: ${goal.goal}",
    before = tally(goal.before),
    beforeFraction = fraction(goal.before),
    now = tally(goal.now),
    nowFraction = fraction(goal.now),
    met = metLine(goal),
    extra = goal.missedAverage?.let { average ->
        // "1 bottles" read wrongly on the device; the unit is stored plural, so one drops the s.
        val unit = goal.unitLabel?.let { " " + if (average == 1.0) it.removeSuffix("s") else it }.orEmpty()
        "On the days you missed, you averaged ${amount(average)}$unit."
    } ?: goal.openWeek?.let { week ->
        val left = week.totalDays - week.elapsedDays
        "This week so far: ${amount(week.observed)} of ${amount(week.target)}, with $left ${if (left == 1) "day" else "days"} to go."
    },
)

/**
 * "Met on 12 of the last 14 days" when every day counted; "Met on 8 of 11 days counted in the last
 * 14" when some did not -- a silent day or a no-opportunity answer is in neither side. Found on the
 * device reading "8 of the last 11 days", which misstated the window.
 */
private fun metLine(goal: TrendGoal): String {
    val total = if (goal.period == Period.WEEK) 2 else 14
    val verb = if (goal.period == Period.WEEK) "Met in" else "Met on"
    return if (goal.now.scored == total) {
        "$verb ${goal.now.met} of the last $total ${goal.unit}"
    } else {
        "$verb ${goal.now.met} of ${goal.now.scored} ${goal.unit} counted in the last $total"
    }
}

private fun tally(t: Tally) = "${t.met} of ${t.scored}"

private fun fraction(t: Tally) = if (t.scored == 0) 0f else t.met.toFloat() / t.scored

private fun percent(v: Double) = "${(v * 100).roundToInt()}%"

/** 1.5 as "1.5", 6400.0 as "6,400": amounts read the way the check-in wrote them. */
private fun amount(v: Double): String =
    if (v >= 1000) NumberFormat.getIntegerInstance(Locale.US).format(v.roundToInt()) else v.asAnswer()
