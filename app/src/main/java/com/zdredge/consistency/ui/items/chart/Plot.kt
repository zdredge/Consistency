package com.zdredge.consistency.ui.items.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdredge.consistency.ui.theme.Ash
import java.time.LocalDate

/**
 * The frame the two plotted charts share: a value axis down the left, a week grid behind, and one
 * column per day across.
 *
 * **A column per day, including the days with nothing in them.** The alternative — plotting only the
 * days that have values — would space four answers evenly across five weeks and draw a tidy chart of
 * a habit nobody kept. The gaps are the information.
 *
 * Canvas text here rather than composables, unlike the calendars: the labels have to sit exactly on
 * the tick they name, and laying that out with composables means measuring twice and agreeing once.
 */
internal class Plot(
    val area: Rect,
    private val min: Float,
    private val max: Float,
    private val days: Int,
) {
    /** The y for a value, clamped into the plot so an outlier draws at the edge rather than outside. */
    fun y(value: Float): Float =
        area.bottom - ((value - min) / (max - min)).coerceIn(0f, 1f) * area.height

    /** The left edge of one day's column. */
    fun x(index: Int): Float = area.left + index * slot

    fun centre(index: Int): Float = x(index) + slot / 2

    val slot: Float get() = area.width / days
}

internal val axisTextStyle = TextStyle(fontSize = 9.sp, color = Ash)

/**
 * Draws the axis, its ticks, and a faint separator at each week boundary.
 *
 * The week separators are what make a five-week chart readable as five weeks rather than as
 * thirty-five columns — the calendars get that for free from their rows.
 */
internal fun DrawScope.drawPlotFrame(
    plot: Plot,
    ticks: List<Pair<Float, String>>,
    measurer: TextMeasurer,
    weekBoundaries: List<Int>,
) {
    ticks.forEach { (value, label) ->
        val y = plot.y(value)
        drawLine(
            color = ChartPalette.Grid,
            start = Offset(plot.area.left, y),
            end = Offset(plot.area.right, y),
            strokeWidth = 1f,
        )
        val laid: TextLayoutResult = measurer.measure(label, axisTextStyle)
        drawText(
            laid,
            topLeft = Offset(plot.area.left - laid.size.width - 4.dp.toPx(), y - laid.size.height / 2),
        )
    }

    weekBoundaries.drop(1).forEach { index ->
        val x = plot.x(index)
        drawLine(
            color = ChartPalette.Grid,
            start = Offset(x, plot.area.top),
            end = Offset(x, plot.area.bottom),
            strokeWidth = 1f,
        )
    }
}

/** The target, as a line across the plot with its value written at the end of it. */
internal fun DrawScope.drawTargetLine(
    plot: Plot,
    target: Float,
    label: String,
    measurer: TextMeasurer,
) {
    val y = plot.y(target)
    drawLine(
        color = Ash,
        start = Offset(plot.area.left, y),
        end = Offset(plot.area.right, y),
        strokeWidth = 1.dp.toPx(),
    )
    val laid = measurer.measure(label, axisTextStyle)
    drawText(laid, topLeft = Offset(plot.area.right + 4.dp.toPx(), y - laid.size.height / 2))
}

/** The Monday of each week under the plot, so a column can be placed in time. */
internal fun DrawScope.drawWeekLabels(
    plot: Plot,
    weekBoundaries: List<Int>,
    dates: List<LocalDate>,
    measurer: TextMeasurer,
    format: java.time.format.DateTimeFormatter,
) {
    weekBoundaries.forEach { index ->
        val date = dates.getOrNull(index) ?: return@forEach
        val laid = measurer.measure(date.format(format), axisTextStyle)
        drawText(
            laid,
            topLeft = Offset(plot.x(index), plot.area.bottom + 4.dp.toPx()),
        )
    }
}

/**
 * Round tick values that cover `0..max`, with headroom above it.
 *
 * Steps run to twenty-odd thousand and coffee to five, and an axis labelled 0, 4,266, 8,532 is an
 * axis nobody reads. This picks a 1/2/5-times-a-power-of-ten step, the convention every chart axis
 * has used since before any of this.
 *
 * **[minStep] stops the axis subdividing below what the values mean.** Both charts that use this
 * plot counts, and a maximum of two coffees produced a step of 0.5 — four ticks reading 0, 0, 1, 2, 2
 * once each was rounded to a whole number, which is an axis with two pairs of duplicate labels on it.
 * Found on the device.
 *
 * The scale always reaches one step past the data, so the tallest bar stops short of the frame rather
 * than merging with it.
 */
internal fun niceTicks(max: Float, wanted: Int = 4, minStep: Float = 0f): List<Float> {
    if (max <= 0f) return listOf(0f, maxOf(minStep, 1f))
    val rough = max / wanted
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(rough.toDouble()))).toFloat()
    val step = maxOf(
        minStep,
        when {
            rough / magnitude <= 1f -> magnitude
            rough / magnitude <= 2f -> 2 * magnitude
            rough / magnitude <= 5f -> 5 * magnitude
            else -> 10 * magnitude
        },
    )
    val ticks = generateSequence(0f) { it + step }.takeWhile { it < max - step / 100f }.toList()
    return ticks + (ticks.last() + step) + (ticks.last() + 2 * step)
}

/** `8000` reads as `8,000`. An axis of bare digits is one the eye has to parse rather than read. */
internal fun Float.axisLabel(): String =
    if (this >= 1000f) "%,.0f".format(this) else "%.0f".format(this)
