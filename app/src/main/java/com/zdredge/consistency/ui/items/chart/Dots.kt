package com.zdredge.consistency.ui.items.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.detail.DayCell
import com.zdredge.consistency.domain.detail.DayValue
import com.zdredge.consistency.domain.detail.NightFilter
import com.zdredge.consistency.domain.detail.TrendPoint
import com.zdredge.consistency.domain.time.ClockAxis
import com.zdredge.consistency.ui.theme.Accent
import com.zdredge.consistency.ui.theme.Bone
import com.zdredge.consistency.ui.theme.Ink
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter

private val weekLabelFormat = DateTimeFormatter.ofPattern("d MMM")
private val clockFormat = DateTimeFormatter.ofPattern("h:mm a")

/**
 * Sleep times as dots on an axis that starts at 04:00.
 *
 * **Each night stands alone, so they are dots and not a line.** A line from 23:00 to 01:30 claims a
 * bedtime at every minute in between, and there was only ever the two.
 *
 * The axis is the whole point of the view. Measured from 04:00 — the app's day boundary — a 01:30
 * bedtime is the latest night on the chart; measured from midnight it would be the earliest, and a
 * month of late nights would plot as a month of early ones. `ClockAxis` owns that conversion and this
 * never does its own clock arithmetic.
 *
 * The trend is the **rolling average over the nights the filter shows**, and it draws a point only on
 * a night that was answered, so several blank nights read as a gap rather than as a straight line
 * bridging them.
 */
@Composable
internal fun ClockDotsChart(
    nights: List<DayCell>,
    allDays: List<DayCell>,
    trend: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
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

    // An hour of air either side, snapped to the hour, so a dot never sits on the frame. The range
    // is taken from the data rather than fixed: a chart of bedtimes and a chart of wake-ups are the
    // same view over completely different hours.
    val low = (minutes.min() / 60f).toInt() * 60f - 60f
    val high = ((minutes.max() / 60f).toInt() + 1) * 60f + 60f
    val ticks = generateSequence(low) { it + 60f }
        .takeWhile { it <= high }
        .map { it to ClockAxis.timeAt(it.toDouble()).format(clockFormat) }
        .toList()

    val byDay = nights.associateBy { it.day }
    val boundaries = allDays.indices.filter { it % 7 == 0 }

    Box(
        modifier
            .fillMaxWidth()
            .height(170.dp)
            .drawBehind {
                val plot = Plot(
                    area = Rect(
                        left = 52.dp.toPx(),
                        top = 6.dp.toPx(),
                        right = size.width - 8.dp.toPx(),
                        bottom = size.height - 16.dp.toPx(),
                    ),
                    min = low,
                    max = high,
                    days = allDays.size,
                )

                drawPlotFrame(plot, ticks, measurer, boundaries)

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
                    if (started) {
                        drawPath(path, color = Bone, style = Stroke(width = 1.8.dp.toPx()), alpha = 0.85f)
                    }
                }

                allDays.forEachIndexed { index, cell ->
                    val time = (byDay[cell.day]?.value as? DayValue.TimeOfDay)?.value
                        ?: return@forEachIndexed
                    drawCircle(
                        color = Accent,
                        radius = 3.5.dp.toPx(),
                        center = Offset(plot.centre(index), plot.y(ClockAxis.minuteOf(time).toFloat())),
                    )
                    // A hairline of the ground around each dot, so two adjacent nights stay two dots.
                    drawCircle(
                        color = Ink,
                        radius = 3.5.dp.toPx(),
                        center = Offset(plot.centre(index), plot.y(ClockAxis.minuteOf(time).toFloat())),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }

                drawWeekLabels(plot, boundaries, allDays.map { it.day }, measurer, weekLabelFormat)
            },
    )
}

/**
 * Which nights the sleep charts show: **Every night · Sun–Thu · Custom**.
 *
 * It opens on Sun–Thu every time rather than remembering the last choice, so a filter set weeks ago
 * can never quietly hide nights. Custom starts from whatever is already showing, so adding Friday is
 * one tap rather than seven.
 *
 * Sun–Thu is the nights before a working day for **all three** sleep items, and that is only true
 * because every sleep answer is filed under the night the user went to bed (spec §3.1): Monday
 * morning's wake-up belongs to Sunday night.
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
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        // Four chips do not fit one phone-width row -- "Average" wrapped to "Avera/ge" on the device.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DayOfWeek.entries.forEach { day ->
                    val on = day in filter.nights
                    FilterChip(
                        selected = on,
                        onClick = {
                            onChange(
                                NightFilter.Custom(
                                    if (on) filter.nights - day else filter.nights + day,
                                ),
                            )
                        },
                        label = { Text(day.name.take(3).lowercase().replaceFirstChar(Char::uppercase)) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
    }
}
