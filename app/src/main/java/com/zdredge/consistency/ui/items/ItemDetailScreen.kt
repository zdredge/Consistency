package com.zdredge.consistency.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One item's history.
 *
 * **Phase 3: the figures and the table, with the chart named and not drawn.** Splitting it that way
 * was deliberate — the figures are what the product is actually for, and getting them on a real
 * device before any drawing code exists means a wrong number is a wrong number rather than something
 * to blame the canvas for.
 *
 * Figures show **from day one**, unlike the dashboard, which suppresses everything until 14 days of
 * history exist (spec §5.5). That was the user's call and it overruled the opening position: watching
 * a rate move while it settles is itself information.
 */
@Composable
fun ItemDetailScreen(
    state: ItemDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("← Items") }

        if (state.loading) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        if (state.missing) {
            Text("That item is no longer in the library.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Text(state.prompt, style = MaterialTheme.typography.titleLarge)
        Text(
            state.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            item { ChartPlaceholder(state.chartToCome) }

            item {
                Text(
                    state.windowLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(state.figures) { FigureRow(it) }

            item {
                Text(
                    "Every day",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }

            if (state.rows.isEmpty()) {
                item {
                    Text(
                        "Nothing recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.rows) { HistoryRowLine(it) }

            // The gesture-navigation bar sits over the last row otherwise.
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/**
 * Where the chart goes, naming the view the rule picked.
 *
 * Worth its own space rather than nothing at all: this is the only place a device pass can check that
 * `ItemViews.derive` chose what §5.4 agreed, before there is any drawing to confuse the question.
 */
@Composable
private fun ChartPlaceholder(view: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                view,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "drawn in the next phase",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One figure and, beneath it, the number that stops it being read wrongly.
 *
 * A hit rate of 100% over two scored days and one over fourteen are the same percentage and not the
 * same fact, so the denominator is never dropped.
 */
@Composable
private fun FigureRow(figure: Figure) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 16.dp)) {
            Text(figure.label, style = MaterialTheme.typography.bodyMedium)
            figure.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
