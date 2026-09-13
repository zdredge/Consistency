package com.zdredge.consistency.ui.items.chart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.detail.ActivityRow
import com.zdredge.consistency.domain.detail.DayCell
import com.zdredge.consistency.domain.detail.DayState
import com.zdredge.consistency.domain.detail.WeekAnswer
import com.zdredge.consistency.ui.theme.Bone
import com.zdredge.consistency.ui.theme.Graphite
import com.zdredge.consistency.ui.theme.Outline
import java.time.LocalDate
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
 * evening was.
 *
 * Rows are a fixed height rather than a share of the screen: stretched to fill one, four strips of
 * thirty-five slivers read as a barcode.
 */
@Composable
internal fun ActivityRowsChart(
    rows: List<ActivityRow>,
    days: List<DayCell>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            // The flagged row is set apart rather than merely coloured: it is the goal, and the
            // others are context for it.
            if (row.flagged) Box(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    // Two lines and an ellipsis. One line cut "Scrolled on phone" to "Scrolled on",
                    // and that is the activity the goal is about.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(104.dp).padding(end = 8.dp),
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    days.forEach { cell ->
                        val picked = cell.day in row.picked
                        val isSelected = cell.day == selected
                        Box(
                            Modifier
                                .weight(1f)
                                .height(24.dp)
                                .then(
                                    if (cell.isSelectable()) {
                                        Modifier.clickable(onClickLabel = "Show this night") { onSelect(cell.day) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .drawBehind {
                                    // A night before the item existed gets a dim dot, the mark the
                                    // calendar uses: drawing nothing left the strip looking as
                                    // though it began in the middle of the chart.
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
                                    drawRoundRect(color = fill, cornerRadius = CornerRadius(2.dp.toPx()))
                                    // A selected night lightens the whole column, so it reads across
                                    // all four rows at once rather than as four separate outlines.
                                    if (isSelected) {
                                        drawRoundRect(
                                            color = Bone.copy(alpha = 0.4f),
                                            cornerRadius = CornerRadius(2.dp.toPx()),
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A weekly question: one square a week.
 *
 * Asked once, in Sunday night's check-in, so a day calendar would be six squares in seven empty. The
 * squares use the same marks as every other calendar, which is what lets a week read as *no
 * opportunity* rather than as a miss. Square, sized from the width, and capped at 64dp — past that
 * five squares stop reading as a row of weeks and start reading as buttons.
 */
@Composable
internal fun WeekSquaresChart(
    weeks: List<WeekAnswer>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val square = ((maxWidth - 32.dp) / 5).coerceAtMost(64.dp)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            weeks.forEach { week ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(square)
                            .then(
                                if (week.cell.isSelectable()) {
                                    Modifier.clickable(onClickLabel = "Show this week") { onSelect(week.cell.day) }
                                } else {
                                    Modifier
                                },
                            )
                            .drawBehind {
                                if (week.cell.day == selected) drawSelection()
                                drawCell(week.cell, dayCalendarFill(week.cell))
                            },
                    )
                    Text(
                        week.weekStart.format(weekLabelFormat),
                        style = MaterialTheme.typography.labelMedium,
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
 * another and not that the brighter one reached the target. The word "target" rather than an arrow,
 * which said the same thing less plainly.
 */
@Composable
internal fun ShadeKey(
    labels: List<String>,
    ramp: List<Color>,
    targetFrom: Int?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .drawBehind {
                            drawRoundRect(
                                color = ramp.getOrElse(index) { ramp.last() },
                                cornerRadius = CornerRadius(4.dp.toPx()),
                            )
                        },
                )
                Row(Modifier.padding(top = 4.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (targetFrom != null && index == targetFrom) {
                        Text(
                            " target",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
