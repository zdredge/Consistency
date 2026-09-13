package com.zdredge.consistency.ui.items

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.scoring.inForce
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One item in the list.
 *
 * The title is the item's **prompt**, because that is the only name an item has. It makes for long
 * rows — "Did you take time when time could be taken?" — and the alternative would be inventing a
 * short label that nothing stores, which would then disagree with the check-in. Naming items is a
 * settings-add-on question; until then the question is the name.
 */
data class ItemRow(
    val itemId: ItemId,
    val prompt: String,
    /** When it is asked and whether it is scored — "Night · Goal", "Measured". */
    val subtitle: String,
)

data class ItemsUiState(
    val loading: Boolean = true,
    val rows: List<ItemRow> = emptyList(),
)

/**
 * The list of items, in the order the check-ins ask them.
 *
 * **Deliberately without figures.** A list with a hit rate against each row is the dashboard, which
 * is M10 and has rings, panels and thresholds behind it — and `HomeScreen` already carries a note
 * saying this half of the app must not grow toward it in the meantime. This screen exists to reach
 * an item's history; the judgements live on the item's own screen, where they have room to be
 * reported honestly beside their attainment.
 */
class ItemsViewModel(
    private val repository: ConsistencyRepository,
    private val dayResolver: DayResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(ItemsUiState())
    val state: StateFlow<ItemsUiState> = _state.asStateFlow()

    /**
     * Where the list was scrolled to.
     *
     * **Held here because the screen leaves composition entirely when an item is opened**, so
     * `rememberLazyListState` has nothing to remember it in — found on the device, by scrolling to
     * Steps, opening it, coming back, and being at the top of the list. `remember` survives a
     * recomposition; this survives the screen being replaced, which is a different question.
     *
     * A Compose type in a ViewModel is a smell worth naming. It holds no Context and no lifecycle, so
     * it leaks nothing; the alternative is storing an index and a pixel offset here and copying them
     * into a state on the way in and out, which is the same thing written twice.
     */
    val listState: LazyListState = LazyListState()

    suspend fun refresh() {
        val today = dayResolver.today()
        val versions = repository.versions()

        val rows = repository.items()
            // Spec §4's listing order, stored as `ordinal` since M4 — the order the questions are
            // asked in, which is chronological through the night rather than alphabetical by id.
            .sortedBy { it.ordinal }
            .mapNotNull { item ->
                // The version in force today, not the latest: what the item is now is what the list
                // should call it, and on a retired item "now" is whatever it last was.
                val version = versions.inForce(item.id, today) ?: return@mapNotNull null
                ItemRow(
                    itemId = item.id,
                    prompt = version.prompt,
                    subtitle = subtitleFor(item.kind, version.slot, version.classification),
                )
            }

        _state.value = ItemsUiState(loading = false, rows = rows)
    }
}
