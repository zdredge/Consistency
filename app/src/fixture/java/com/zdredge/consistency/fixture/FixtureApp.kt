package com.zdredge.consistency.fixture

import com.zdredge.consistency.AppContainer
import com.zdredge.consistency.ConsistencyApp

/**
 * The application class of the fixture install (M9), and only of it.
 *
 * The real app with two differences: it never arms check-in prompts, because it shares the phone
 * with the real app; and its steps come from [FixtureStepSource], so a generated step history is
 * never overwritten by the user's real steps.
 */
class FixtureApp : ConsistencyApp() {

    val store: FixtureStore by lazy { FixtureStore(this) }

    override val schedulesPrompts: Boolean = false

    override fun createContainer(): AppContainer = AppContainer(
        applicationContext,
        stepSourceFor = { FixtureStepSource },
        repositoryFor = { resolver, steps -> store.repository(resolver, steps) },
    )
}
