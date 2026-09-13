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
import java.time.LocalDate

/** One figure, and the second number that keeps it from being read wrongly. */
data class Figure(val label: String, val value: String, val detail: String? = null)

/**
 * The day a tap selected, as the card beneath the chart shows it (spec §5.4): the day, its answer,
 * how it was recorded, and its note.
 */
data class DayCardUi(
    val date: String,
    val what: String,
    val how: String,
    val note: String?,
)

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
    /** The tapped day, outlined on the chart. Null shows the figures instead of a day. */
    val selectedDay: LocalDate? = null,
    val dayCard: DayCardUi? = null,
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

    /** Not remembered between visits, for the same reason as the filter. */
    private var selected: LocalDate? = null

    /**
     * Called as the item is tapped, before the screen composes.
     *
     * **This is what removes the shutter.** The ViewModel outlives the screen, so opening an item
     * used to render the *previous* item's chart for a frame, then a blank while the read ran, then
     * the new one — three different things in a few hundred milliseconds. The list already knows the
     * prompt and the subtitle, so the header can be right immediately and only the body has to wait.
     */
    fun open(prompt: String, subtitle: String) {
        _state.value = ItemDetailUiState(loading = true, prompt = prompt, subtitle = subtitle)
    }

    suspend fun load(itemId: ItemId) {
        // Whatever `open` put there stays: clearing it here would reintroduce the blank frame.
        _state.value = ItemDetailUiState(
            loading = true,
            prompt = _state.value.prompt,
            subtitle = _state.value.subtitle,
        )

        val loaded = repository.itemHistory(itemId, dayResolver.today())
        history = loaded
        filter = NightFilter.OPENING
        showTrend = true
        selected = null

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

    /** Tapping the selected day again closes it, which is the gesture the spec's mockups used. */
    fun selectDay(day: LocalDate) {
        selected = if (selected == day) null else day
        present()
    }

    fun clearDay() {
        selected = null
        present()
    }

    private fun present() {
        val loaded = history ?: return
        val today = dayResolver.today()
        _state.value = presentItemDetail(
            loaded,
            ItemDetails.assemble(loaded, today, dayResolver, filter),
            selected,
        ).copy(showTrend = showTrend)
    }
}
