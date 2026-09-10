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
    /**
     * The 04:00 job has not run in too long, so every figure derived from check-ins is drifting.
     *
     * Architecture §8 rates a silently failing rollover "High — invisible" and asks for staleness to
     * be surfaced **in the app rather than only in logs**. This is that surface: nothing else in the
     * product would ever tell the user, because the failure mode is that everything still looks fine.
     */
    val rolloverOverdue: Boolean = false,
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
private const val OverdueAfterDays = 2L

class HomeViewModel(
    private val repository: ConsistencyRepository,
    private val dayResolver: DayResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * Whether the daily job has been silent for longer than it should be.
     *
     * Two cases, and the second is the one worth the extra query. A job that **ran and then stopped**
     * is caught by the timestamp. A job that has **never run at all** leaves no timestamp, and on a
     * fresh install that is correct and unremarkable — so the oldest check-in dates the install, and
     * only an install old enough to have had a rollover due counts as overdue. Without that, a job
     * broken since day one would look exactly like one that simply is not due yet, which is the
     * invisible failure this is here to make visible.
     */
    private suspend fun isRolloverOverdue(today: LocalDate): Boolean {
        val installedOn = repository.earliestCheckInDay() ?: return false
        if (!installedOn.isBefore(today.minusDays(OverdueAfterDays))) return false

        val lastRun = repository.lastSuccessfulRollover() ?: return true
        return dayResolver.dayFor(lastRun).isBefore(today.minusDays(OverdueAfterDays))
    }

    /**
     * Suspends rather than launching into `viewModelScope`, so the caller can do something *after*
     * it.
     *
     * That mattered as soon as alarms existed: this created today's check-in rows, and the alarm
     * scheduler had nothing to schedule until it had. Launching meant the scheduler ran first and set
     * nothing, so on the first open of a day no prompt was armed until something else happened to
     * trigger a reschedule. Found on the device, by opening the app once instead of twice.
     *
     * **That fix was too narrow, and the same bug was found twice more.** The scheduler now
     * guarantees the rows itself (`checkInsForAlarms`), so no caller depends on this ordering any
     * more. It stays suspending because the screen's state should still be read after the write that
     * produces it, not because anything else is waiting.
     */
    suspend fun refresh() {
        val today = dayResolver.today()
        repository.seedLibraryIfEmpty(today)
        repository.ensureCheckInsExist(today)

        _state.value = HomeUiState(
            loading = false,
            today = today,
            outstanding = repository.outstandingCheckIns(today),
            itemCount = repository.items().size,
            rolloverOverdue = isRolloverOverdue(today),
        )
    }
}
