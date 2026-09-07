package com.zdredge.consistency.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeUiState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    /** Check-ins that are due, unanswered and still inside the backfill grace (spec §3.2). */
    val outstanding: List<CheckIn> = emptyList(),
    val itemCount: Int = 0,
)

/**
 * The landing screen's state holder.
 *
 * It also owns the two things that must happen whenever the app is opened, in this order:
 *
 * 1. **Seed the library** if this is a first run (spec §4).
 * 2. **Generate any missing check-ins** through today. Days the user never opened the app on must
 *    still produce rows, or a skipped week has no denominator and skipping *improves* response rate
 *    (architecture §5). M5 moves this to a scheduled worker so it happens without the app being
 *    opened at all; until then, opening is the only trigger there is.
 */
class HomeViewModel(
    private val repository: ConsistencyRepository,
    private val dayResolver: DayResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val today = dayResolver.today()
            repository.seedLibraryIfEmpty(today)
            repository.ensureCheckInsExist(today)

            _state.value = HomeUiState(
                loading = false,
                today = today,
                outstanding = repository.outstandingCheckIns(today),
                itemCount = repository.items().size,
            )
        }
    }
}
