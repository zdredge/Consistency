package com.zdredge.consistency

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.data.db.createConsistencyDatabase
import com.zdredge.consistency.domain.time.DayResolver
import com.zdredge.consistency.ui.checkin.CheckInViewModel
import com.zdredge.consistency.ui.home.HomeViewModel
import java.time.Clock

/**
 * Manual constructor injection — deliberately not a DI framework.
 *
 * docs/architecture.md section 4: at five screens a DI framework is not needed, and the whole object
 * graph being visible in one place is the point. Hilt is worth adopting only once several
 * ViewModels need the same dependencies, and that is an ask-first decision (CLAUDE.md).
 *
 * The graph is four objects: the clock, the day resolver everything dates through, the repository
 * that is the only way in and out of storage, and a factory so the two ViewModels survive rotation.
 * Note the database is not exposed — `:data` builds it (see `createConsistencyDatabase`) so Room
 * stays inside that module and nothing here can reach past the repository to a DAO.
 */
class AppContainer(
    context: Context,
    /** Production wall clock. Tests substitute `Clock.fixed(...)`. */
    clock: Clock = Clock.systemDefaultZone(),
) {
    /** The single day resolver. Nothing else may compute which day a timestamp belongs to. */
    val dayResolver: DayResolver = DayResolver(clock)

    /** The single way in and out of storage. */
    val repository: ConsistencyRepository =
        ConsistencyRepository(createConsistencyDatabase(context), dayResolver)

    /**
     * Hand-written rather than generated, and hand-written rather than skipped.
     *
     * Plain state holders held in `remember` would be simpler, but they die on rotation — and losing
     * a half-finished check-in to a screen turn is exactly the friction spec §1 says ends the
     * product. `ViewModel` was already on the classpath, so this costs a factory and no dependency.
     */
    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(repository, dayResolver) as T
            modelClass.isAssignableFrom(CheckInViewModel::class.java) ->
                CheckInViewModel(repository, dayResolver) as T
            else -> error("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
