package com.zdredge.consistency

import android.content.Context
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.data.db.createConsistencyDatabase
import com.zdredge.consistency.domain.time.DayResolver
import java.time.Clock

/**
 * Manual constructor injection — deliberately not a DI framework.
 *
 * docs/architecture.md section 4: at five screens a DI framework is not needed, and the whole object
 * graph being visible in one place is the point. Hilt is worth adopting only once several
 * ViewModels need the same dependencies, and that is an ask-first decision (CLAUDE.md).
 *
 * At M3 the graph is three objects: the clock, the day resolver everything dates through, and the
 * repository that is the only way in and out of storage. Note the database itself is not exposed —
 * `:data` builds it (see `createConsistencyDatabase`) so Room stays inside that module and nothing
 * here can reach past the repository to a DAO.
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
}
