package com.zdredge.consistency.ui.items

import androidx.lifecycle.ViewModel
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.domain.detail.Chart
import com.zdredge.consistency.domain.detail.DayCell
import com.zdredge.consistency.domain.detail.ItemDetails
import com.zdredge.consistency.domain.detail.ItemHistory
import com.zdredge.consistency.domain.detail.NightFilter
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
    /** What the chart is, for the views that are not drawn yet. */
    val chartToCome: String = "",
    /** The chart's own data, or null while the item is loading. */
    val chart: Chart? = null,
    /** The five weeks of judged days every view is drawn from. */
    val days: List<DayCell> = emptyList(),
    /** Whether the sleep chart's rolling average is drawn. On by default (spec §5.4). */
    val showTrend: Boolean = true,
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

    /** Held so the filter can re-assemble without going back to the database for the same rows. */
    private var history: ItemHistory? = null

    /**
     * **Opened on Sun–Thu every time, never remembered.** Spec §5.4: a filter set weeks ago would be
     * hiding nights nobody had asked it to hide, so it resets with every visit to the item.
     */
    private var filter: NightFilter = NightFilter.OPENING

    /** The rolling average is on by default, with a switch to hide it (spec §5.4). */
    private var showTrend: Boolean = true

    suspend fun load(itemId: ItemId) {
        _state.value = ItemDetailUiState(loading = true)

        val loaded = repository.itemHistory(itemId, dayResolver.today())
        history = loaded
        filter = NightFilter.OPENING
        showTrend = true

        if (loaded == null) {
            _state.value = ItemDetailUiState(loading = false, missing = true)
            return
        }
        present()
    }

    /**
     * Re-assembles on the new filter rather than mutating what is on screen.
     *
     * The average and the typical time are computed from the nights shown, so they have to move with
     * it; the recording count must not, and does not, because `RecordingCount` takes no filter at all.
     */
    fun setFilter(next: NightFilter) {
        filter = next
        present()
    }

    fun setShowTrend(show: Boolean) {
        showTrend = show
        present()
    }

    private fun present() {
        val loaded = history ?: return
        val today = dayResolver.today()
        _state.value = presentItemDetail(
            loaded,
            ItemDetails.assemble(loaded, today, dayResolver, filter),
        ).copy(showTrend = showTrend)
    }
}
