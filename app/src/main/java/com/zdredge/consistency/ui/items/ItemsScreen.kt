package com.zdredge.consistency.ui.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Every item, as a way in to its history.
 *
 * Plain on purpose, and it must stay that way. A row carrying a hit rate is the dashboard — M10, with
 * the three rings and the panels and the 14-day suppression behind it — and a list that grows a
 * figure per row arrives at a worse version of it without ever deciding to. The judgements belong on
 * the item's own screen, where hit rate can sit beside the attainment that keeps it honest.
 */
@Composable
fun ItemsScreen(
    state: ItemsUiState,
    listState: LazyListState,
    onOpenItem: (ItemRow) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("← Home") }

        Text(
            "Items",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (state.loading) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.rows, key = { it.itemId.value }) { row ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenItem(row) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(row.prompt, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            row.subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // The gesture-navigation bar sits over the last card otherwise.
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
