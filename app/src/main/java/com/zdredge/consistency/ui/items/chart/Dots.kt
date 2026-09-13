package com.zdredge.consistency.ui.items.chart

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.detail.DayCell
import com.zdredge.consistency.domain.detail.DayValue
import com.zdredge.consistency.domain.detail.NightFilter
import com.zdredge.consistency.domain.detail.TrendPoint
import com.zdredge.consistency.domain.time.ClockAxis
import com.zdredge.consistency.ui.theme.Accent
import com.zdredge.consistency.ui.theme.Bone
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val weekLabelFormat = DateTimeFormatter.ofPattern("d MMM")
private val hourFormat = DateTimeFormatter.ofPattern("h a")

/** Wide enough for "12 AM" at 12sp. */
private val ClockAxisWidth = 44.dp

/**
 * Sleep times as dots on an axis that starts at 04:00.
 *
 * **Each night stands alone, so they are dots and not a line.** A line from 23:00 to 01:30 claims a
 * bedtime at every minute in between.
 *
 * Measured from 04:00 — the app's day boundary — a 01:30 bedtime is the latest night on the chart;
 * measured from midnight it would be the earliest. `ClockAxis` owns that conversion and this does no
 * clock arithmetic of its own.
 *
 * **4:3**, like the bars, rather than stretched to a share of the screen. The trend is the rolling
 * average over the nights the filter shows, with a point only on an answered night, so a run of
 * blank nights reads as a gap rather than a bridge.
 */
@Composable
internal fun ClockDotsChart(
    nights: List<DayCell>,
    allDays: List<DayCell>,
    trend: List<TrendPoint>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    ground: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = axisTextStyle()
    val minutes = nights.mapNotNull { (it.value as? DayValue.TimeOfDay)?.value }
        .map { ClockAxis.minuteOf(it).toFloat() }

    if (minutes.isEmpty()) {
        Text(
            "No times recorded yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 16.dp),
        )
        return
    }

    // An hour of air either side, snapped to the hour, so a dot never sits on the frame. Taken from
    // the data: bedtimes and wake-ups are the same view over completely different hours.
    val low = (minutes.min() / 60f).toInt() * 60f - 60f
    val high = ((minutes.max() / 60f).toInt() + 1) * 60f + 60f
    val ticks = generateSequence(low) { it + 60f }
        .takeWhile { it <= high }
        .map { it to ClockAxis.timeAt(it.toDouble()).format(hourFormat) }
        .toList()

    val byDay = nights.associateBy { it.day }
    val boundaries = allDays.indices.filter { it % 7 == 0 }
    val currentDays = rememberUpdatedState(allDays)
    val shownDays = rememberUpdatedState(byDay.keys)
    val currentSelect = rememberUpdatedState(onSelect)

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val area = Rect(
                        ClockAxisWidth.toPx(), PlotInsets.Top.toPx(),
                        size.width - PlotInsets.Right.toPx(), size.height - PlotInsets.Bottom.toPx(),
                    )
                    val index = Plot(area, 0f, 1f, currentDays.value.size).indexAt(offset.x) ?: return@detectTapGestures
                    val day = currentDays.value[index]
                    // Only a night the filter shows: tapping a hidden Friday would select a column
                    // with nothing drawn in it.
                    if (day.day in shownDays.value && day.isSelectable()) currentSelect.value(day.day)
                }
            }
            .drawBehind {
                val plot = Plot(
                    area = Rect(
                        left = ClockAxisWidth.toPx(),
                        top = PlotInsets.Top.toPx(),
                        right = size.width - PlotInsets.Right.toPx(),
                        bottom = size.height - PlotInsets.Bottom.toPx(),
                    ),
                    min = low,
                    max = high,
                    days = allDays.size,
                )

                drawPlotFrame(plot, ticks, measurer, style, boundaries)

                val selectedIndex = allDays.indexOfFirst { it.day == selected }
                if (selectedIndex >= 0) {
                    drawLine(
                        color = Bone.copy(alpha = 0.5f),
                        start = Offset(plot.centre(selectedIndex), plot.area.top),
                        end = Offset(plot.centre(selectedIndex), plot.area.bottom),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // The line first, so a dot sits on top of it rather than under.
                if (trend.isNotEmpty()) {
                    val path = Path()
                    var started = false
                    trend.forEach { point ->
                        val index = allDays.indexOfFirst { it.day == point.night }
                        if (index < 0) return@forEach
                        val x = plot.centre(index)
                        val y = plot.y(point.minute.toFloat())
                        if (started) {
                            path.lineTo(x, y)
                        } else {
                            path.moveTo(x, y)
                            started = true
                        }
                    }
                    if (started) drawPath(path, color = Bone, style = Stroke(width = 2.dp.toPx()), alpha = 0.8f)
                }

                allDays.forEachIndexed { index, cell ->
                    val cellShown = byDay[cell.day] ?: return@forEachIndexed
                    val time = (cellShown.value as? DayValue.TimeOfDay)?.value ?: return@forEachIndexed
                    val centre = Offset(plot.centre(index), plot.y(ClockAxis.minuteOf(time).toFloat()))
                    if (cell.day == selected) {
                        drawCircle(color = Bone, radius = 8.dp.toPx(), center = centre, style = Stroke(width = 2.dp.toPx()))
                    }
                    drawCircle(color = Accent, radius = 4.dp.toPx(), center = centre)
                    // A ring of the card's own colour, so two adjacent nights stay two dots.
                    drawCircle(color = ground, radius = 4.dp.toPx(), center = centre, style = Stroke(width = 1.5.dp.toPx()))
                    if (cellShown.marks.hasNote) {
                        drawCircle(color = Bone, radius = 2.dp.toPx(), center = Offset(centre.x, centre.y - 9.dp.toPx()))
                    }
                }

                drawWeekLabels(plot, boundaries, allDays.map { it.day }, measurer, style, weekLabelFormat)
            },
    )
}

/**
 * Which nights the sleep charts show: **Every night · Sun–Thu · Custom**, and the average switch.
 *
 * It opens on Sun–Thu every time rather than remembering the last choice, so a filter set weeks ago
 * can never quietly hide nights. Custom starts from whatever is already showing.
 *
 * Sun–Thu is the nights before a working day for **all three** sleep items, and that is only true
 * because every sleep answer is filed under the night the user went to bed (spec §3.1).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NightFilterControl(
    filter: NightFilter,
    onChange: (NightFilter) -> Unit,
    showTrend: Boolean,
    onShowTrend: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // Four chips do not fit one phone-width row, so they wrap.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == NightFilter.EveryNight,
                onClick = { onChange(NightFilter.EveryNight) },
                label = { Text("Every night") },
            )
            FilterChip(
                selected = filter == NightFilter.SundayToThursday,
                onClick = { onChange(NightFilter.SundayToThursday) },
                label = { Text("Sun–Thu") },
            )
            FilterChip(
                selected = filter is NightFilter.Custom,
                onClick = { onChange(NightFilter.customFrom(filter)) },
                label = { Text("Custom") },
            )
            // Spec §5.4: the average is shown by default, with a switch to hide it.
            FilterChip(
                selected = showTrend,
                onClick = { onShowTrend(!showTrend) },
                label = { Text("Average") },
            )
        }

        if (filter is NightFilter.Custom) {
            FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val on = day in filter.nights
                    FilterChip(
                        selected = on,
                        onClick = { onChange(NightFilter.Custom(if (on) filter.nights - day else filter.nights + day)) },
                        label = { Text(day.name.take(3).lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
        }
    }
}
