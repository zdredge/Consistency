package com.zdredge.consistency.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdredge.consistency.domain.detail.Chart
import com.zdredge.consistency.domain.detail.NightFilter
import com.zdredge.consistency.ui.items.chart.ActivityRowsChart
import com.zdredge.consistency.ui.items.chart.BarsChart
import com.zdredge.consistency.ui.items.chart.ChartPalette
import com.zdredge.consistency.ui.items.chart.ClockDotsChart
import com.zdredge.consistency.ui.items.chart.DayGrid
import com.zdredge.consistency.ui.items.chart.NightFilterControl
import com.zdredge.consistency.ui.items.chart.ShadeKey
import com.zdredge.consistency.ui.items.chart.WeekSquaresChart
import com.zdredge.consistency.ui.items.chart.dayCalendarFill
import com.zdredge.consistency.ui.items.chart.keyLabels
import com.zdredge.consistency.ui.items.chart.shadedFill
import com.zdredge.consistency.ui.items.chart.targetBucket
import com.zdredge.consistency.ui.items.chart.weeklyTotalLabel
import java.time.LocalDate

/**
 * One item's history: its chart on a card, and the daily log beneath it.
 *
 * **Laid out from the review of 2026-09-13**, which picked from true-scale drafts rather than from
 * argument. Three decisions carry it:
 *
 * - **The chart and its figures sit on a card; the log sits on the ground.** One separation
 *   technique, a contrasting surface — Material's guidance is a border, a shadow or a surface, and
 *   not all three. The card is the surface the check-in screen already uses.
 * - **The figures are a list under the chart**, name left and value right, a size apart so the two
 *   cannot run together the way 15sp and 13sp did.
 * - **Tapping a day replaces the figures with that day**, in place, directly under the square that
 *   was tapped (spec §5.4). Tapping it again, or ×, brings the figures back.
 *
 * The whole screen scrolls as one. Nothing is sized to a share of the screen: the card is as tall as
 * its chart, and each chart is as tall as its own proportions make it.
 *
 * Figures show **from day one**, unlike the dashboard (spec §5.5) — the user's call.
 */
@Composable
fun ItemDetailScreen(
    state: ItemDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFilter: (NightFilter) -> Unit = {},
    onShowTrend: (Boolean) -> Unit = {},
    onSelectDay: (LocalDate) -> Unit = {},
    onClearDay: () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) { Text("← Items") }
                // The full question, at the size the check-in asks it, wrapping as far as it needs.
                // The header is set from the list before the read lands, so it never flashes.
                Text(
                    state.prompt,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (state.subtitle.isNotEmpty()) {
                    Text(
                        state.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (state.missing) {
            item {
                Text("That item is no longer in the library.", style = MaterialTheme.typography.bodyLarge)
            }
            return@LazyColumn
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 12dp at the sides rather than 16: the review found the axis labels squeezed toward
                // the middle, and the side padding is width the chart's labels need more.
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.loading) {
                        // Holds roughly a calendar's height, so the log does not jump when it lands.
                        Box(Modifier.fillMaxWidth().height(280.dp))
                    } else {
                        ItemChart(state, onFilter, onShowTrend, onSelectDay)
                        val card = state.dayCard
                        if (card != null) {
                            DayCard(card, onClearDay)
                        } else {
                            Figures(state.figures)
                        }
                        Text(
                            state.windowLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (!state.loading) {
            item {
                Text(
                    "Daily Log",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
                )
            }
            if (state.rows.isEmpty()) {
                item {
                    Text(
                        "Nothing recorded yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.rows) { HistoryRowLine(it) }
        }
    }
}

/**
 * The chart, drawn at its own proportions.
 *
 * **Exhaustive over [Chart] with no `else`**, so a view added to the domain cannot reach this screen
 * undrawn — it stops the build instead.
 */
@Composable
private fun ItemChart(
    state: ItemDetailUiState,
    onFilter: (NightFilter) -> Unit,
    onShowTrend: (Boolean) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
) {
    val ground = MaterialTheme.colorScheme.surface
    val selected = state.selectedDay

    when (val chart = state.chart) {
        null -> Unit

        is Chart.DayCalendar ->
            DayGrid(state.days, ::dayCalendarFill, chart.weeks, selected, onSelectDay)

        is Chart.ShadedCalendar -> {
            val ramp = ChartPalette.rampOf(chart.scale.buckets.size)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DayGrid(state.days, shadedFill(chart.shades, ramp), emptyList(), selected, onSelectDay)
                // The key is what makes the ramp mean something rather than merely vary.
                ShadeKey(chart.scale.keyLabels(), ramp, chart.scale.targetBucket())
            }
        }

        is Chart.ActivityRows -> ActivityRowsChart(chart.rows, state.days, selected, onSelectDay)

        is Chart.WeekSquares -> WeekSquaresChart(chart.weeks, selected, onSelectDay)

        is Chart.ClockDots -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ClockDotsChart(
                nights = chart.nights,
                allDays = state.days,
                // Hiding the average hides only the line -- it is a view switch, not a filter.
                trend = if (state.showTrend) chart.trend else emptyList(),
                selected = selected,
                onSelect = onSelectDay,
                ground = ground,
            )
            NightFilterControl(chart.filter, onFilter, state.showTrend, onShowTrend)
        }

        // One chart for both. They differ in the states a measured day can be in, which the cells
        // already carry, and in what their weekly chip says.
        is Chart.DailyBars ->
            BarsChart(state.days, chart.dailyTarget, chart.weeks, ::weeklyTotalLabel, selected, onSelectDay, ground)

        is Chart.StepBars ->
            BarsChart(state.days, chart.dailyTarget, chart.weeks, ::weeklyTotalLabel, selected, onSelectDay, ground)
    }
}

/**
 * The figures: name on the left, value on the right, one row each.
 *
 * 16sp names against 13sp detail and 21sp values — three sizes, plus colour, where there had been
 * 15 and 13 in the same grey. A hit rate is never shown without its denominator: 2 of 3 and 200 of
 * 300 are the same percentage and not the same fact.
 */
@Composable
private fun Figures(figures: List<Figure>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        figures.forEach { figure ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(figure.label, style = MaterialTheme.typography.bodyLarge)
                    figure.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(figure.value, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/**
 * The tapped day, in the figures' place (spec §5.4): when, what was answered, how it was recorded,
 * and its note. On the raised colour one step up from the card, so it reads as the thing that has
 * been opened.
 */
@Composable
private fun DayCard(card: DayCardUi, onClose: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Column(
                Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(card.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(card.what, style = MaterialTheme.typography.bodyLarge)
                Text(card.how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                card.note?.let {
                    Text(
                        "“$it”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
                Text("×", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One day in the log — the only surface that shows every state and every note in bulk (§5.4). */
@Composable
private fun HistoryRowLine(row: HistoryRow) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(row.day, style = MaterialTheme.typography.bodyLarge)
                if (row.marks.isNotEmpty()) {
                    Text(row.marks, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(row.state, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
                if (row.value.isNotEmpty()) {
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        row.note?.let {
            Text(
                "“$it”",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = ChartPalette.Grid)
    }
}

