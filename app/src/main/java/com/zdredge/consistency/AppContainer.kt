package com.zdredge.consistency

import com.zdredge.consistency.domain.time.DayResolver
import java.time.Clock

/**
 * Manual constructor injection — deliberately not a DI framework.
 *
 * docs/architecture.md section 4: at five screens a DI framework is not needed, and the whole object
 * graph being visible in one place is the point. Hilt is worth adopting only once several
 * ViewModels need the same dependencies, and that is an ask-first decision (CLAUDE.md).
 *
 * Tiny at M1 by design: it holds the one clock and the one day resolver. It exists now so that
 * later milestones grow a graph that was always explicit.
 */
class AppContainer(
    /** Production wall clock. Tests substitute `Clock.fixed(...)`. */
    clock: Clock = Clock.systemDefaultZone(),
) {
    /** The single day resolver. Nothing else may compute which day a timestamp belongs to. */
    val dayResolver: DayResolver = DayResolver(clock)
}
