package com.zdredge.consistency.ui.items.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.detail.DayCell
import com.zdredge.consistency.domain.detail.DayState
import com.zdredge.consistency.domain.detail.DayValue
import com.zdredge.consistency.domain.detail.WeekFigure
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.ui.theme.Accent
import com.zdredge.consistency.ui.theme.Ash
import com.zdredge.consistency.ui.theme.Bone
import com.zdredge.consistency.ui.theme.Ink
import com.zdredge.consistency.ui.theme.Outline
import java.time.format.DateTimeFormatter

private val weekLabelFormat = DateTimeFormatter.ofPattern("d MMM")

private val DayNames = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * The calendar every grid view is built from.
 *
 * **Five weeks, Monday first**, because a 14-day calendar is two rows deep and a month of weekday
 * habits is the pattern worth seeing (spec §5.4). The cells arrive already judged — [DayCell] is a
 * verdict, not an answer — so nothing here decides anything beyond which shape and which colour.
 *
 * Drawn in Compose Canvas with no charting library. M8 Phase 1 ended with no item on a line chart,
 * which left Vico — chosen in architecture §4 specifically for line charts — with nothing to draw.
 *
 * Labels are real text composables rather than canvas text: the only drawing here is squares, and
 * measuring glyphs by hand to place four words is work with nothing to show for it.
 */
@Composable
internal fun DayGrid(
    days: List<DayCell>,
    fillOf: (DayCell) -> CellFill,
    weeks: List<WeekFigure>,
    modifier: Modifier = Modifier,
) {
    val rows = days.chunked(7)
    val showTally = weeks.isNotEmpty()
    val labelWidth = 46.dp
    val tallyWidth = 54.dp
    val flagged = weeks.any { it.closed && it.incomplete }

    BoxWithConstraints(modifier.fillMaxWidth()) {
    // The squares grow into whatever height the screen gave the chart -- at a fixed 30dp they were
    // the smallest thing on a page whose point is the chart. **Capped against their own width**,
    // because past that they stop being squares: a calendar of tall rectangles reads as a bar chart,
    // and the first attempt at filling the space produced exactly that.
    val cellWidth = (maxWidth - labelWidth - (if (showTally) tallyWidth else 0.dp) - 24.dp) / 7
    val spare = maxHeight - 20.dp - (if (flagged) 22.dp else 0.dp)
    val cellHeight = (spare / rows.size.coerceAtLeast(1) - 4.dp)
        .coerceIn(22.dp, cellWidth * 1.1f)

    Column(
        Modifier.fillMaxWidth().fillMaxHeight(),
        // Centred, so the leftover sits above and below the grid rather than opening a gap between
        // the calendar and the key that explains it.
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(labelWidth))
            DayNames.forEach { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
            if (showTally) {
                Text(
                    "Week",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(tallyWidth),
                )
            }
        }

        rows.forEachIndexed { index, week ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // The week's Monday, rather than "W3". The mockups numbered weeks because their
                    // data was invented; a real week is worth naming by the date it starts on.
                    week.first().day.format(weekLabelFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(labelWidth),
                )
                Row(Modifier.weight(7f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { cell ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(cellHeight)
                                .drawBehind { drawCell(cell, fillOf(cell)) },
                        )
                    }
                    // A short final week -- today is rarely a Sunday -- keeps its columns rather than
                    // stretching the days it does have across the whole row.
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
                if (showTally) {
                    TallyChip(weeks.getOrNull(index), cellHeight, Modifier.width(tallyWidth))
                }
            }
        }

        // An unexplained glyph is worse than none. Shown only when one is actually on screen.
        if (flagged) {
            Text(
                "○ that week had days nobody answered",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    }
}

/** Labels for a shade key: "0-1", "2", "3", "4+" -- read off the buckets rather than restated. */
internal fun com.zdredge.consistency.domain.detail.ShadeScale.keyLabels(): List<String> =
    buckets.map { bucket ->
        when {
            bucket.toInclusive == null -> "${bucket.from}+"
            bucket.toInclusive == bucket.from -> "${bucket.from}"
            else -> "${bucket.from}–${bucket.toInclusive}"
        }
    }

/** Where the target starts, so the key can mark it (spec 5.4). Null when nothing is a target. */
internal fun com.zdredge.consistency.domain.detail.ShadeScale.targetBucket(): Int? =
    buckets.indexOfFirst { it.reachesTarget }.takeIf { it >= 0 }

/** How one day's square is painted. A ring is an absence; a fill is something that happened. */
internal sealed interface CellFill {
    data class Solid(val color: Color) : CellFill
    data class Ring(val color: Color, val dashed: Boolean = false) : CellFill

    /**
     * A day the item did not exist on.
     *
     * Drawn as a dim dot, not as an empty square and not as nothing. Nothing at all was tried first
     * and, on a three-day-old item, left four of five rows blank and the chart reading as broken; an
     * empty square is the shape a *missed* day would have. A dot holds the grid together and is
     * plainly not a judgement.
     */
    data object Absent : CellFill
}

/**
 * The fill for a day on a yes/no or single-select calendar.
 *
 * A **recorded** day is painted by its answer rather than by a verdict, because there is no verdict:
 * "did you stretch?" is targeted by the week, so a no on a Tuesday is a no, not a miss. That is the
 * distinction the grey is carrying — a missed goal and an honest no look alike here on purpose, and
 * neither is red.
 */
internal fun dayCalendarFill(cell: DayCell): CellFill = when (cell.state) {
    DayState.MET -> CellFill.Solid(Accent)
    DayState.MISSED -> CellFill.Solid(ChartPalette.Missed)
    DayState.NO_OPPORTUNITY -> CellFill.Solid(ChartPalette.NoOpportunity)
    DayState.RECORDED -> when ((cell.value as? DayValue.YesNo)?.value) {
        true -> CellFill.Solid(Accent)
        false -> CellFill.Solid(ChartPalette.Missed)
        null -> CellFill.Solid(ChartPalette.Quiet)
    }
    // Still answerable: an outline, because nothing has been decided about it yet.
    DayState.OPEN -> CellFill.Ring(Accent)
    DayState.DEFERRED -> CellFill.Ring(Ash, dashed = true)
    DayState.NOT_ANSWERED -> CellFill.Ring(Outline)
    DayState.CONFLICTED -> CellFill.Ring(Outline)
    DayState.FUTURE -> CellFill.Ring(ChartPalette.Grid)
    DayState.NOT_ACTIVE -> CellFill.Absent
}

/**
 * The fill for a calendar shaded by amount.
 *
 * The shade comes from the domain's bucket, never from the value here: `ShadeScale.bucketOf` floors a
 * half, so 1.5 bottles is shaded as 1 and can never look like the target of 2 was reached.
 */
internal fun shadedFill(shades: Map<java.time.LocalDate, Int>, ramp: List<Color>): (DayCell) -> CellFill =
    { cell ->
        val bucket = shades[cell.day]
        when {
            bucket != null -> CellFill.Solid(ramp.getOrElse(bucket) { ramp.last() })
            else -> dayCalendarFill(cell)
        }
    }

internal fun DrawScope.drawCell(cell: DayCell, fill: CellFill) {
    val corner = CornerRadius(5.dp.toPx())
    val inset = 1.dp.toPx()

    when (fill) {
        is CellFill.Solid -> drawRoundRect(color = fill.color, cornerRadius = corner)
        is CellFill.Ring -> drawRoundRect(
            color = fill.color,
            topLeft = Offset(inset / 2, inset / 2),
            size = Size(size.width - inset, size.height - inset),
            cornerRadius = corner,
            style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = if (fill.dashed) {
                    PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                } else {
                    null
                },
            ),
        )
        CellFill.Absent -> drawCircle(
            color = Outline,
            radius = 1.5.dp.toPx(),
            center = Offset(size.width / 2, size.height / 2),
        )
    }

    if (fill is CellFill.Absent) return

    // A no-opportunity day carries a dash through it. Constraint 17: leaning on the neutral answer
    // stays visible, and a dim square on its own would read as any other quiet day.
    if (cell.state == DayState.NO_OPPORTUNITY) {
        val half = 5.dp.toPx()
        drawLine(
            color = Ash,
            start = Offset(size.width / 2 - half, size.height / 2),
            end = Offset(size.width / 2 + half, size.height / 2),
            strokeWidth = 2.dp.toPx(),
        )
    }

    val onLight = fill is CellFill.Solid && fill.color.luminance() > 0.4f
    val glyph = if (onLight) Ink else Bone

    // Spec §5.4 puts these two on the chart and leaves late answers and edits to the table.
    if (cell.marks.backfilled) {
        val notch = 8.dp.toPx()
        val path = Path().apply {
            moveTo(size.width - 3.dp.toPx() - notch, 3.dp.toPx())
            lineTo(size.width - 3.dp.toPx(), 3.dp.toPx())
            lineTo(size.width - 3.dp.toPx(), 3.dp.toPx() + notch)
            close()
        }
        drawPath(path, glyph)
    }
    if (cell.marks.hasNote) {
        drawCircle(
            color = glyph,
            radius = 2.dp.toPx(),
            center = Offset(size.width / 2, size.height - 5.dp.toPx()),
        )
    }
}

/**
 * A week's count or total beside its row — `4/6` closed, `2 of 6` while it is still running.
 *
 * Spec §3.4: **a week with unanswered days is flagged wherever its figure appears.** Four workouts
 * across a week with two blank days is four, not four-of-five, and the flag is what stops the figure
 * being read as the second thing.
 */
@Composable
private fun TallyChip(week: WeekFigure?, height: Dp, modifier: Modifier = Modifier) {
    if (week == null || !week.active || week.value == null) {
        Spacer(modifier)
        return
    }

    val value = week.value ?: return
    val target = week.target?.valueNumber
    val text = when {
        target == null -> value.short()
        // A closed week is a verdict and reads as one; an open week is progress and says so.
        week.closed -> "${value.short()}/${target.short()}"
        else -> "${value.short()} of ${target.short()}"
    }
    val met = week.result?.outcome == GoalOutcome.MET

    Box(
        modifier
            .height(height)
            .background(
                color = if (met) ChartPalette.Tint else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .drawBehind {
                if (met) return@drawBehind
                drawRoundRect(
                    color = if (week.closed) Outline else Ash,
                    topLeft = Offset(0.75.dp.toPx(), 0.75.dp.toPx()),
                    size = Size(size.width - 1.5.dp.toPx(), size.height - 1.5.dp.toPx()),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = if (week.closed) {
                            null
                        } else {
                            PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                        },
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // The ring flags a *closed* week that had unanswered days (spec §3.4). An open week is
            // incomplete by definition and its dashed border already says so, so flagging that too
            // would put a mark on every running week and teach the user to ignore it.
            if (week.incomplete && week.closed) "$text ○" else text,
            style = MaterialTheme.typography.labelSmall,
            color = if (met) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Double.short(): String =
    if (this % 1.0 == 0.0) "%.0f".format(this) else "%.1f".format(this)
