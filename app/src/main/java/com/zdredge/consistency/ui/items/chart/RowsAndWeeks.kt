package com.zdredge.consistency.ui.items.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdredge.consistency.domain.detail.ActivityRow
import com.zdredge.consistency.domain.detail.DayCell
import com.zdredge.consistency.domain.detail.DayState
import com.zdredge.consistency.domain.detail.WeekAnswer
import com.zdredge.consistency.ui.theme.Graphite
import com.zdredge.consistency.ui.theme.Outline
import java.time.format.DateTimeFormatter

private val weekLabelFormat = DateTimeFormatter.ofPattern("d MMM")

/**
 * *What did you do before bed?* — one row per activity, a column per night.
 *
 * **A night can hold several activities, which one calendar square cannot show.** That is the whole
 * reason this view exists rather than a calendar: reading a book *and* scrolling is one night and two
 * facts, and a single square has to drop one of them.
 *
 * The flagged activity comes last and in red, which is the user's explicit choice and the only red on
 * any chart in the app. Every other activity is quiet — they are not achievements, they are what the
 * evening was — and the target is an absence rule on the one that is.
 */
@Composable
internal fun ActivityRowsChart(rows: List<ActivityRow>, days: List<DayCell>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
    // The strips grow into the height the chart was given. At a fixed 14dp they were a footnote on a
    // screen whose whole point is the chart.
    val rowHeight = (maxHeight / rows.size.coerceAtLeast(1) - 10.dp).coerceIn(14.dp, 56.dp)

    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = if (row.flagged) 0.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.option.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    // Two lines and an ellipsis. The seeded labels are "Watched YouTube" and
                    // "Scrolled on phone", which a single line cut to "Watched" and "Scrolled on" --
                    // and the second of those is the one the goal is about.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 12.sp,
                    modifier = Modifier.width(104.dp).padding(end = 6.dp),
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    days.forEach { cell ->
                        val picked = cell.day in row.picked
                        Box(
                            Modifier
                                .weight(1f)
                                .height(rowHeight)
                                .drawBehind {
                                    // A night before the item existed gets a dim dot, the same mark
                                    // the calendar uses. Drawing nothing was tried first and left the
                                    // strip looking as though it began three days ago in the middle
                                    // of the chart.
                                    if (cell.state == DayState.NOT_ACTIVE) {
                                        drawCircle(
                                            color = Outline,
                                            radius = 1.dp.toPx(),
                                            center = Offset(size.width / 2, size.height / 2),
                                        )
                                        return@drawBehind
                                    }
                                    // A night that happened without this activity is a quiet ground,
                                    // not a gap: the question was asked and this was not the answer.
                                    val fill = when {
                                        cell.state == DayState.FUTURE -> ChartPalette.Grid
                                        picked && row.flagged -> ChartPalette.Flagged
                                        picked -> ChartPalette.Quiet
                                        else -> Graphite
                                    }
                                    drawRoundRect(
                                        color = fill,
                                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                                    )
                                },
                        )
                    }
                }
            }
            // The flagged row is set apart rather than merely coloured: it is the goal, and the
            // others are context for it.
            if (row.flagged) Box(Modifier.height(6.dp))
        }
    }
    }
}

/**
 * A weekly question: one square a week.
 *
 * Asked once, in Sunday night's check-in, so a day calendar would be six squares in seven empty. The
 * squares use the same marks as every other calendar, which is what lets a week read as *no
 * opportunity* rather than as a miss.
 */
@Composable
internal fun WeekSquaresChart(weeks: List<WeekAnswer>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
    // Five squares across a phone are limited by width, not height, so this grows only until they
    // would stop being squares.
    val squareHeight = (maxHeight - 24.dp).coerceIn(44.dp, maxWidth / 5 + 8.dp)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weeks.forEach { week ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(squareHeight)
                        .drawBehind { drawCell(week.cell, dayCalendarFill(week.cell)) },
                )
                Text(
                    week.weekStart.format(weekLabelFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    }
}

/**
 * The key beneath a shaded calendar, marking **where the target starts** (spec §5.4).
 *
 * Without it the ramp is a gradient with no meaning: the user can see that one day is brighter than
 * another and not that the brighter one reached the target.
 */
@Composable
internal fun ShadeKey(
    labels: List<String>,
    ramp: List<Color>,
    targetFrom: Int?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .drawBehind {
                            drawRoundRect(
                                color = ramp.getOrElse(index) { ramp.last() },
                                cornerRadius = CornerRadius(3.dp.toPx()),
                            )
                        },
                )
                Text(
                    if (targetFrom != null && index == targetFrom) "$label ↑" else label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
