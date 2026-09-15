package com.zdredge.consistency.fixture

import android.content.Context
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.data.db.createConsistencyDatabase
import com.zdredge.consistency.data.health.StepSource
import com.zdredge.consistency.domain.time.DayResolver
import java.time.Clock

/**
 * The fixture install's one database, shared by the app's repository and the scenario loader.
 *
 * One Room instance rather than two: two instances over the same file do not see each other's
 * writes, so screens built on the app's repository would go on showing the history from before a
 * load. Keeping the database here also keeps its type out of `:app`, which has no Room on its
 * classpath and should not need it.
 *
 * It is the same file name as the real app's, and that is safe only because this runs under a
 * different app id -- a separate data directory entirely.
 */
class FixtureStore(context: Context) {

    private val database = createConsistencyDatabase(context.applicationContext)

    fun repository(dayResolver: DayResolver, stepSource: StepSource): ConsistencyRepository =
        ConsistencyRepository(database, dayResolver, stepSource = stepSource)

    /** Generates [scenario] for [clock]'s now and replaces this install's history with it. */
    suspend fun load(scenario: Scenario, clock: Clock = Clock.systemDefaultZone()): FixtureLoader.Loaded =
        FixtureLoader(database, DayResolver(clock)).load(scenario.generate(clock))
}
