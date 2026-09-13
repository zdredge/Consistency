package com.zdredge.consistency.ui.items.chart

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.ui.theme.Ash
import com.zdredge.consistency.ui.theme.Bone
import java.time.LocalDate

/** Plot insets shared by bars and dots, so the two plots have one geometry rather than two. */
internal object PlotInsets {
    val Top = 12.dp
    val Bottom = 24.dp
    val Right = 4.dp
}

/**
 * The frame the two plotted charts share: a value axis down the left, a week grid behind, and one
 * column per day across.
 *
 * **A column per day, including the days with nothing in them.** Plotting only the days that have
 * values would space four answers evenly across five weeks and draw a tidy chart of a habit nobody
 * kept. The gaps are the information.
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

    /** The column under a tap, or null when the tap landed on the axis or past the last day. */
    fun indexAt(x: Float): Int? =
        if (x < area.left || x > area.right) null else ((x - area.left) / slot).toInt().coerceIn(0, days - 1)
}

/**
 * Axis text, from the theme rather than a size typed at the call site.
 *
 * It was a hardcoded 9sp — the smallest type anywhere in the app — for the axis values, the target and
 * the week dates. `labelSmall` is now a chosen 12sp, the floor for anything on screen.
 */
@Composable
@ReadOnlyComposable
internal fun axisTextStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(color = Ash)

/**
 * Draws the axis, its ticks, and a faint separator at each week boundary.
 *
 * The week separators are what make a five-week chart readable as five weeks rather than as
 * thirty-five columns — the calendars get that for free from their rows. Hairlines are 1dp: they were
 * a raw 1px, a third of a dp on this phone, while the target line beside them was a full dp.
 */
internal fun DrawScope.drawPlotFrame(
    plot: Plot,
    ticks: List<Pair<Float, String>>,
    measurer: TextMeasurer,
    style: TextStyle,
    weekBoundaries: List<Int>,
) {
    val hairline = 1.dp.toPx()
    ticks.forEachIndexed { index, (value, label) ->
        val y = plot.y(value)
        drawLine(
            color = if (index == 0) ChartPalette.Quiet else ChartPalette.Grid,
            start = Offset(plot.area.left, y),
            end = Offset(plot.area.right, y),
            strokeWidth = hairline,
        )
        val laid = measurer.measure(label, style)
        drawText(
            laid,
            topLeft = Offset(plot.area.left - laid.size.width - 6.dp.toPx(), y - laid.size.height / 2),
        )
    }

    weekBoundaries.drop(1).forEach { index ->
        val x = plot.x(index)
        drawLine(
            color = ChartPalette.Grid,
            start = Offset(x, plot.area.top),
            end = Offset(x, plot.area.bottom),
            strokeWidth = hairline,
        )
    }
}

/**
 * The target, as a dashed line across the plot, labelled **inside** it at the left.
 *
 * The label used to sit in a 44dp gutter to the right of the plot, which cost every bar that width.
 * Inside, it is drawn over a halo of the card's own colour so it stays legible where a bar passes
 * behind it.
 */
internal fun DrawScope.drawTargetLine(
    plot: Plot,
    target: Float,
    label: String,
    measurer: TextMeasurer,
    style: TextStyle,
    ground: Color,
) {
    val y = plot.y(target)
    drawLine(
        color = Bone.copy(alpha = 0.8f),
        start = Offset(plot.area.left, y),
        end = Offset(plot.area.right, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
    )
    // One layout, drawn twice with the colour and stroke given at draw time. Measuring twice with a
    // different colour and stroke in the style does not work: the measurer caches the layout on what
    // affects shape, so both passes came back as the light fill and the halo rendered as white blobs.
    val laid = measurer.measure(label, style)
    val topLeft = Offset(plot.area.left + 6.dp.toPx(), y - 6.dp.toPx() - laid.size.height)
    drawText(laid, color = ground, topLeft = topLeft, drawStyle = Stroke(width = 4.dp.toPx()))
    // Fill must be stated: the stroke from the pass above stays on the paragraph's paint otherwise,
    // and the light text is drawn as a 4dp outline too.
    drawText(laid, color = Bone, topLeft = topLeft, drawStyle = Fill)
}

/** The Monday of each week under the plot, so a column can be placed in time. */
internal fun DrawScope.drawWeekLabels(
    plot: Plot,
    weekBoundaries: List<Int>,
    dates: List<LocalDate>,
    measurer: TextMeasurer,
    style: TextStyle,
    format: java.time.format.DateTimeFormatter,
) {
    weekBoundaries.forEach { index ->
        val date = dates.getOrNull(index) ?: return@forEach
        val laid = measurer.measure(date.format(format), style)
        drawText(laid, topLeft = Offset(plot.x(index) + 2.dp.toPx(), plot.area.bottom + 6.dp.toPx()))
    }
}

/**
 * Round tick values that cover `0..max`, with headroom above it.
 *
 * Steps run to twenty-odd thousand and coffee to five, and an axis labelled 0, 4,266, 8,532 is an
 * axis nobody reads. This picks a 1/2/5-times-a-power-of-ten step.
 *
 * **[minStep] stops the axis subdividing below what the values mean.** Both charts that use this
 * plot counts, and a maximum of two coffees produced a step of 0.5 — ticks reading 0, 0, 1, 2, 2 once
 * each was rounded to a whole number. Found on the device.
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
    // Up to the first tick that covers the data, and one step further only when the data lands
    // exactly on it -- a full-height bar merged with the frame otherwise.
    val below = generateSequence(0f) { it + step }.takeWhile { it < max }.toList()
    val top = below.last() + step
    return if (top - max < step / 100f) below + top + (top + step) else below + top
}

/**
 * `8000` reads as `8k`, `12500` as `12.5k`.
 *
 * Abbreviated rather than grouped: the axis gutter is what the plot pays for its labels, and
 * "10,000" costs twice the width of "10k" on every row of a chart whose width is the scarce thing.
 */
internal fun Float.axisLabel(): String {
    if (this < 1000f) return "%.0f".format(this)
    // Rounded before the whole-number check, or 66,989 reads "67.0k".
    val tenths = Math.round(this / 100f)
    return if (tenths % 10 == 0) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k"
}
