package com.zdredge.consistency.ui.items.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import com.zdredge.consistency.ui.theme.Bone
import com.zdredge.consistency.ui.theme.Outline
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val weekLabelFormat = DateTimeFormatter.ofPattern("d MMM")

/** Width of the value axis. Shared by the canvas and the chips beneath, so each chip sits under its week. */
private val AxisWidth = 36.dp

/**
 * A bar a day, with the daily target as a line across them — coffee and steps.
 *
 * **4:3, not whatever height is left.** An earlier version stretched the plot to fill a fixed share of
 * the screen, and a bar chart's aspect ratio is not a free variable: it decides how one day's height
 * compares with another's, which is the whole of what the chart says.
 *
 * **Two limits cannot share an axis without one being misread**, which is why the weekly figure sits
 * in its own row beneath rather than as a second line on the plot (spec 7.1–7.2).
 *
 * Steps adds the two states only a measured day has. A **provisional** day is a dimmer bar — the
 * figure is real and may still change. A day **two sources reported** is an empty outline the full
 * height of the plot: a short bar would claim the user walked nowhere.
 */
@Composable
internal fun BarsChart(
    days: List<DayCell>,
    dailyTarget: Double?,
    weeks: List<WeekFigure>,
    weekLabel: (WeekFigure) -> String,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    ground: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = axisTextStyle()
    val values = days.mapNotNull { (it.value as? DayValue.Amount)?.value?.toFloat() }
    val top = maxOf(values.maxOrNull() ?: 0f, dailyTarget?.toFloat() ?: 0f, 1f)
    // Counts, both of them: coffees and steps. Never a half of either on the axis.
    val ticks = niceTicks(top, minStep = 1f)
    val axisMax = maxOf(ticks.last(), top)
    val boundaries = days.indices.filter { it % 7 == 0 }
    val currentDays = rememberUpdatedState(days)
    val currentSelect = rememberUpdatedState(onSelect)

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val area = plotArea(size.width.toFloat(), size.height.toFloat())
                        val index = Plot(area, 0f, 1f, currentDays.value.size).indexAt(offset.x) ?: return@detectTapGestures
                        val cell = currentDays.value[index]
                        if (cell.isSelectable()) currentSelect.value(cell.day)
                    }
                }
                .drawBehind {
                    val plot = Plot(plotArea(size.width, size.height), 0f, axisMax, days.size)
                    drawPlotFrame(plot, ticks.map { it to it.axisLabel() }, measurer, style, boundaries)
                    drawBars(plot, days, axisMax, selected)
                    dailyTarget?.let {
                        drawTargetLine(plot, it.toFloat(), "${it.toFloat().axisLabel()} a day", measurer, style, ground)
                    }
                    drawWeekLabels(plot, boundaries, days.map { it.day }, measurer, style, weekLabelFormat)
                },
        )

        if (weeks.isNotEmpty()) {
            WeeklyTotals(weeks, weekLabel, Modifier.padding(start = AxisWidth, end = PlotInsets.Right))
        }
    }
}

private fun DrawScope.plotArea(width: Float, height: Float): Rect = Rect(
    left = AxisWidth.toPx(),
    top = PlotInsets.Top.toPx(),
    right = width - PlotInsets.Right.toPx(),
    bottom = height - PlotInsets.Bottom.toPx(),
)

private fun androidx.compose.ui.input.pointer.PointerInputScope.plotArea(width: Float, height: Float): Rect = Rect(
    left = AxisWidth.toPx(),
    top = PlotInsets.Top.toPx(),
    right = width - PlotInsets.Right.toPx(),
    bottom = height - PlotInsets.Bottom.toPx(),
)

private fun DrawScope.drawBars(
    plot: Plot,
    days: List<DayCell>,
    axisMax: Float,
    selected: LocalDate?,
) {
    val barWidth = (plot.slot - 2.dp.toPx()).coerceAtLeast(2.dp.toPx())
    val radius = CornerRadius(2.dp.toPx())

    days.forEachIndexed { index, cell ->
        val left = plot.x(index) + (plot.slot - barWidth) / 2

        if (cell.day == selected) {
            val out = 2.dp.toPx()
            drawRoundRect(
                color = Bone,
                topLeft = Offset(left - out, plot.area.top - out),
                size = Size(barWidth + out * 2, plot.area.height + out * 2),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        // A day the app cannot count honestly: an outline the height of the plot, never a short bar.
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

        // A deferred day has no value to draw -- a dashed stub on the baseline says it was put off.
        if (cell.state == DayState.DEFERRED) {
            val stub = 12.dp.toPx()
            drawRoundRect(
                color = Ash,
                topLeft = Offset(left, plot.area.bottom - stub),
                size = Size(barWidth, stub),
                cornerRadius = radius,
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
            )
            return@forEachIndexed
        }

        val value = (cell.value as? DayValue.Amount)?.value?.toFloat() ?: return@forEachIndexed
        val height = (value.coerceAtMost(axisMax) / axisMax) * plot.area.height
        val barTop = plot.area.bottom - height
        if (height > 0f) {
            drawRoundRect(
                // O4: provisional scores exactly like frozen, so the bar is a real bar -- dimmer
                // because the figure may still change, not because it counts for less.
                color = if (cell.marks.provisional) AccentMuted else Accent,
                topLeft = Offset(left, barTop),
                size = Size(barWidth, height),
                cornerRadius = radius,
            )
        }

        // Spec 5.4's marks, which the bars had none of: a note is a dot above the bar, and a
        // backfilled day a short cap in the ground colour across its top.
        if (cell.marks.hasNote) {
            drawCircle(color = Bone, radius = 2.dp.toPx(), center = Offset(left + barWidth / 2, barTop - 5.dp.toPx()))
        }
        if (cell.marks.backfilled && height > 4.dp.toPx()) {
            drawLine(
                color = Outline,
                start = Offset(left, barTop + 3.dp.toPx()),
                end = Offset(left + barWidth, barTop + 3.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

/**
 * Each week's total, beneath its own stretch of the plot.
 *
 * A met week is filled and reads as an answer; an open one is dashed and reads as a state of play.
 */
@Composable
private fun WeeklyTotals(
    weeks: List<WeekFigure>,
    label: (WeekFigure) -> String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        weeks.forEach { week ->
            val met = week.result?.outcome == GoalOutcome.MET
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .background(
                        color = if (met) ChartPalette.Tint else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .drawBehind {
                        if (met || !week.active) return@drawBehind
                        drawRoundRect(
                            color = if (week.closed) Outline else Ash,
                            topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                            size = Size(size.width - 1.dp.toPx(), size.height - 1.dp.toPx()),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = if (week.closed) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
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
                    maxLines = 1,
                )
            }
        }
    }
}

/** `12/14` once a week has closed, `8 of 14` while it is running, and the bare total with no target. */
internal fun weeklyTotalLabel(week: WeekFigure): String {
    if (!week.active) return ""
    val value = week.value ?: return ""
    val shown = value.toFloat().axisLabel()
    val target = week.target?.valueNumber ?: return shown
    val targetShown = target.toFloat().axisLabel()
    return if (week.closed) "$shown/$targetShown" else "$shown of $targetShown"
}
