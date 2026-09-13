package com.zdredge.consistency.ui.items.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.detail.DayCell
import com.zdredge.consistency.domain.detail.DayState
import com.zdredge.consistency.domain.detail.DayValue
import com.zdredge.consistency.domain.detail.WeekFigure
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.ui.theme.Accent
import com.zdredge.consistency.ui.theme.AccentMuted
import com.zdredge.consistency.ui.theme.Ash
import java.time.format.DateTimeFormatter

private val weekLabelFormat = DateTimeFormatter.ofPattern("d MMM")

/**
 * The plot's insets, shared by the canvas and the row of weekly chips beneath it.
 *
 * They are constants rather than two sets of numbers because the chips have to sit under the weeks
 * they describe. Laid out across the full width they drifted right of their own bars, which is a
 * chart quietly labelling the wrong week.
 */
private val AxisWidth = 34.dp
private val TargetLabelWidth = 44.dp

/**
 * A bar a day, with the daily target as a line across them — coffee and steps.
 *
 * **Two limits cannot share an axis without one being misread**, which is why the weekly figure sits
 * in its own row beneath rather than as a second line on the plot: coffee's cap of 2 a day and its cap
 * of 14 a week are different questions, scored separately (7.1–7.2), and drawing them together
 * invites the reader to compare two numbers that have nothing to say to each other.
 *
 * Steps adds the two states only a measured day has. A **provisional** day is a dimmer bar — the
 * figure is real and may still change. A day **two sources reported** is an empty outline the full
 * height of the plot: the app will not draw a number it does not believe, and a zero-height bar would
 * claim the user walked nowhere.
 */
@Composable
internal fun BarsChart(
    days: List<DayCell>,
    dailyTarget: Double?,
    weeks: List<WeekFigure>,
    weekLabel: (WeekFigure) -> String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val values = days.mapNotNull { (it.value as? DayValue.Amount)?.value?.toFloat() }
    val top = maxOf(values.maxOrNull() ?: 0f, dailyTarget?.toFloat() ?: 0f, 1f)
    // Counts, both of them: coffees and steps. Never a half of either on the axis.
    val ticks = niceTicks(top, minStep = 1f)
    val axisMax = maxOf(ticks.last(), top)
    val boundaries = days.indices.filter { it % 7 == 0 }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .drawBehind {
                    val plot = Plot(
                        area = Rect(
                            left = AxisWidth.toPx(),
                            top = 6.dp.toPx(),
                            right = size.width - TargetLabelWidth.toPx(),
                            bottom = size.height - 16.dp.toPx(),
                        ),
                        min = 0f,
                        max = axisMax,
                        days = days.size,
                    )

                    drawPlotFrame(plot, ticks.map { it to it.axisLabel() }, measurer, boundaries)
                    drawBars(plot, days, axisMax)
                    dailyTarget?.let {
                        drawTargetLine(plot, it.toFloat(), it.toFloat().axisLabel(), measurer)
                    }
                    drawWeekLabels(plot, boundaries, days.map { it.day }, measurer, weekLabelFormat)
                },
        )

        if (weeks.isNotEmpty()) {
            WeeklyTotals(
                weeks,
                weekLabel,
                Modifier.padding(start = AxisWidth, end = TargetLabelWidth),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(
    plot: Plot,
    days: List<DayCell>,
    axisMax: Float,
) {
    val barWidth = (plot.slot - 2.dp.toPx()).coerceAtLeast(2.dp.toPx())
    val radius = CornerRadius(1.5.dp.toPx())

    days.forEachIndexed { index, cell ->
        val left = plot.x(index) + (plot.slot - barWidth) / 2

        // A day the app cannot count honestly: an outline the height of the plot. Not a short bar --
        // the origins' total is not a step count, and drawing it would inflate the chart.
        if (cell.state == DayState.CONFLICTED) {
            drawRoundRect(
                color = Ash,
                topLeft = Offset(left, plot.area.top),
                size = Size(barWidth, plot.area.height),
                cornerRadius = radius,
                style = Stroke(width = 1.dp.toPx()),
            )
            return@forEachIndexed
        }

        val value = (cell.value as? DayValue.Amount)?.value?.toFloat() ?: return@forEachIndexed
        val height = (value.coerceAtMost(axisMax) / axisMax) * plot.area.height
        if (height <= 0f) return@forEachIndexed

        drawRoundRect(
            // O4: provisional scores exactly like frozen, so the bar is a real bar -- dimmer, because
            // the figure may still change, not because it counts for less.
            color = if (cell.marks.provisional) AccentMuted else Accent,
            topLeft = Offset(left, plot.area.bottom - height),
            size = Size(barWidth, height),
            cornerRadius = radius,
        )
    }
}

/**
 * Each week's total, beneath its own stretch of the plot.
 *
 * A met week is filled and reads as an answer; an open one is dashed and reads as a state of play.
 * The same chip the calendars use, so a weekly figure looks the same wherever it appears.
 */
@Composable
private fun WeeklyTotals(
    weeks: List<WeekFigure>,
    label: (WeekFigure) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        weeks.forEach { week ->
            val met = week.result?.outcome == GoalOutcome.MET
            Box(
                Modifier
                    .weight(1f)
                    .height(26.dp)
                    .background(
                        color = if (met) ChartPalette.Tint else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .drawBehind {
                        if (met || !week.active) return@drawBehind
                        drawRoundRect(
                            color = if (week.closed) ChartPalette.Grid else Ash,
                            topLeft = Offset(0.75.dp.toPx(), 0.75.dp.toPx()),
                            size = Size(size.width - 1.5.dp.toPx(), size.height - 1.5.dp.toPx()),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = if (week.closed) {
                                    null
                                } else {
                                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(3.dp.toPx(), 3.dp.toPx()),
                                    )
                                },
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(week),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (met) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** `12/14` once a week has closed, `8 of 14` while it is running, and the bare total with no target. */
internal fun weeklyTotalLabel(week: WeekFigure): String {
    if (!week.active) return ""
    val value = week.value ?: return ""
    val shown = if (value >= 1000) "%,.0f".format(value) else "%.0f".format(value)
    val target = week.target?.valueNumber ?: return shown
    val targetShown = if (target >= 1000) "%,.0f".format(target) else "%.0f".format(target)
    return if (week.closed) "$shown/$targetShown" else "$shown of $targetShown"
}
