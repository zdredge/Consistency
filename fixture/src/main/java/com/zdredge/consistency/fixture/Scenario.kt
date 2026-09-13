package com.zdredge.consistency.fixture

/**
 * One named, loadable state of the app's history (build-order M9).
 *
 * Each exists to exercise one behaviour that is impractical to wait for — the 14-day boundary, a
 * retired item, a two-origin step day — so it can be checked in an afternoon rather than lived
 * through.
 */
interface Scenario {
    /** Stable, lower_snake_case. What the developer screen lists and a test names. */
    val id: String

    /** One line on what state this manufactures, shown under the id. */
    val summary: String
}

/** Every scenario the developer screen offers, in the order it lists them. */
object Scenarios {
    val all: List<Scenario> = emptyList()
}
