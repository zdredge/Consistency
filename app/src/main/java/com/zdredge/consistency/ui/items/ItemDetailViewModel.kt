package com.zdredge.consistency.ui.items

import androidx.lifecycle.ViewModel
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.domain.detail.ItemDetails
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One figure, and the second number that keeps it from being read wrongly. */
data class Figure(val label: String, val value: String, val detail: String? = null)

/** One day in the table. */
data class HistoryRow(
    val day: String,
    val state: String,
    val value: String,
    val marks: String,
    val note: String?,
)

data class ItemDetailUiState(
    val loading: Boolean = true,
    /** The item is gone from the library entirely — only reachable through a stale back stack. */
    val missing: Boolean = false,
    val prompt: String = "",
    val subtitle: String = "",
    /** The 14 days every figure covers, spelled out, because §5.2 says they all cover the same ones. */
    val windowLabel: String = "",
    val chartToCome: String = "",
    val figures: List<Figure> = emptyList(),
    val rows: List<HistoryRow> = emptyList(),
)

/**
 * One item's screen.
 *
 * **Wiring, and no more than that.** Every judgement was made by `ItemDetails.assemble` in `:domain`,
 * where it is tested; the strings are made by `presentItemDetail`, which is a function precisely so a
 * preview can reach it. What is left here is a read and two calls. `:app` has no tests, so anything
 * decided here is decided untested — and the figures on this screen are what the product is for.
 */
class ItemDetailViewModel(
    private val repository: ConsistencyRepository,
    private val dayResolver: DayResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(ItemDetailUiState())
    val state: StateFlow<ItemDetailUiState> = _state.asStateFlow()

    suspend fun load(itemId: ItemId) {
        _state.value = ItemDetailUiState(loading = true)

        val today = dayResolver.today()
        val history = repository.itemHistory(itemId, today)
        if (history == null) {
            _state.value = ItemDetailUiState(loading = false, missing = true)
            return
        }

        _state.value = presentItemDetail(history, ItemDetails.assemble(history, today, dayResolver))
    }
}
