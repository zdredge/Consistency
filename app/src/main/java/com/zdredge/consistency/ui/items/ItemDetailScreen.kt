package com.zdredge.consistency.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * How much of the screen the chart gets.
 *
 * **The chart is the point of this screen.** At the size it was first drawn — a strip above a long
 * list of numbers — it was the smallest thing on the page, on the one device this app runs on. The
 * figures and the table are what you read *after* seeing the shape of the month.
 *
 * 0.55 rather than the 0.6 first tried: at 0.6 the table's three rows fell below the fold on the one
 * device this runs on, and a default view you have to scroll to reach is not a default view.
 */
private const val CHART_SHARE = 0.55f

/** About three entries, which is what the table shows before it needs scrolling. */
private val TableHeight = 170.dp

/**
 * One item's history: its chart, its figures, and a table of every day.
 *
 * Figures show **from day one**, unlike the dashboard, which suppresses everything until 14 days of
 * history exist (spec §5.5). That was the user's call and it overruled the opening position: watching
 * a rate move while it settles is itself information.
 *
 * The chart is pinned to the top at a fixed share of the screen and everything beneath it scrolls.
 * The table scrolls **inside its own box** rather than running off the end of the page, so reading
 * back through a month does not push the figures out of sight.
 */
@Composable
fun ItemDetailScreen(
    state: ItemDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFilter: (NightFilter) -> Unit = {},
    onShowTrend: (Boolean) -> Unit = {},
) {
    val chartHeight = LocalConfiguration.current.screenHeightDp.dp * CHART_SHARE

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) { Text("← Items") }

        // The header comes from the list, before the read finishes, so opening an item does not
        // flash the previous one's name on the way in.
        Text(
            state.prompt,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.subtitle.isNotEmpty()) {
            Text(
                state.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.missing) {
            Text(
                "That item is no longer in the library.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        // The chart keeps its space while the read runs, so nothing below it jumps when it lands.
        Box(Modifier.fillMaxWidth().height(chartHeight).padding(top = 8.dp)) {
            if (!state.loading) ItemChart(state, onFilter, onShowTrend)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (state.loading) {
                Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                return@Column
            }

            Text(
                state.windowLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )

            state.figures.forEach { FigureRow(it) }

            Text(
                "Every Day",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )

            if (state.rows.isEmpty()) {
                Text(
                    "Nothing recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(TableHeight)) {
                    items(state.rows) { HistoryRowLine(it) }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The chart, drawn into whatever height the screen gave it.
 *
 * **Exhaustive over [Chart] with no `else`**, so a view added to the domain cannot reach this screen
 * undrawn — it stops the build instead.
 */
@Composable
private fun ItemChart(
    state: ItemDetailUiState,
    onFilter: (NightFilter) -> Unit,
    onShowTrend: (Boolean) -> Unit,
) {
    when (val chart = state.chart) {
        null -> Unit

        is Chart.DayCalendar ->
            DayGrid(state.days, ::dayCalendarFill, chart.weeks, Modifier.fillMaxHeight())

        is Chart.ShadedCalendar -> {
            val ramp = ChartPalette.rampOf(chart.scale.buckets.size)
            Column(Modifier.fillMaxHeight()) {
                DayGrid(
                    state.days,
                    shadedFill(chart.shades, ramp),
                    emptyList(),
                    Modifier.weight(1f),
                )
                // The key is what makes the ramp mean something rather than merely vary.
                ShadeKey(chart.scale.keyLabels(), ramp, chart.scale.targetBucket())
            }
        }

        is Chart.ActivityRows ->
            ActivityRowsChart(chart.rows, state.days, Modifier.fillMaxHeight())

        is Chart.WeekSquares -> WeekSquaresChart(chart.weeks, Modifier.fillMaxHeight())

        is Chart.ClockDots -> Column(Modifier.fillMaxHeight()) {
            ClockDotsChart(
                nights = chart.nights,
                allDays = state.days,
                // Hiding the average hides only the line. The nights, the typical time and the
                // recording count are untouched -- it is a view switch, not a filter.
                trend = if (state.showTrend) chart.trend else emptyList(),
                modifier = Modifier.weight(1f),
            )
            NightFilterControl(chart.filter, onFilter, state.showTrend, onShowTrend)
        }

        // One chart for both. They differ in the states a measured day can be in, which the cells
        // already carry, and in what their weekly chip says.
        is Chart.DailyBars -> BarsChart(
            state.days, chart.dailyTarget, chart.weeks, ::weeklyTotalLabel, Modifier.fillMaxHeight(),
        )

        is Chart.StepBars -> BarsChart(
            state.days, chart.dailyTarget, chart.weeks, ::weeklyTotalLabel, Modifier.fillMaxHeight(),
        )
    }
}

/**
 * One figure and, beneath it, the number that stops it being read wrongly.
 *
 * A hit rate of 100% over two scored days and one over fourteen are the same percentage and not the
 * same fact, so the denominator is never dropped. The label sits a size above its own detail line:
 * set at the same weight the two ran together and the screen read as a wall of grey.
 */
@Composable
private fun FigureRow(figure: Figure) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).padding(end = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                figure.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // On one line with its label rather than beneath it. Stacked, three figures and a table
            // of three days would not fit one phone screen alongside a chart that is the point of
            // the page; weight and colour carry the distinction instead of a line break.
            figure.detail?.let {
                Text(
                    "  $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(figure.value, style = MaterialTheme.typography.titleMedium)
    }
}

/** One day in the table — the only surface that shows every state and every note in bulk (§5.4). */
@Composable
private fun HistoryRowLine(row: HistoryRow) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.padding(end = 16.dp)) {
                Text(row.day, style = MaterialTheme.typography.bodyMedium)
                if (row.marks.isNotEmpty()) {
                    Text(
                        row.marks,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column {
                Text(
                    row.state,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (row.value.isNotEmpty()) {
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        row.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
