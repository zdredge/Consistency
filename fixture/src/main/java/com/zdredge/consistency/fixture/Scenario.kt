package com.zdredge.consistency.fixture

import java.time.Clock

/**
 * One named, loadable state of the app's history (build-order M9).
 *
 * Each exists to exercise one behaviour that is impractical to wait for -- the 14-day boundary, a
 * retired item, a two-origin step day -- so it can be checked in an afternoon rather than lived
 * through.
 */
interface Scenario {
    /** Stable, lower_snake_case. What the developer screen lists and a test names. */
    val id: String

    /** One line on what state this manufactures, shown under the id. */
    val summary: String

    /** The whole history, ending at [clock]'s now. The same clock always gives the same history. */
    fun generate(clock: Clock): FixtureDataset
}

/** Every scenario the developer screen offers, in the order it lists them. */
object Scenarios {

    /**
     * About six months (spec O7): enough for month-over-month trends and comparisons. 183 days is
     * half a year to the day, not a round number chosen for its own sake.
     */
    val SixMonths: Scenario = history(
        id = "six_months",
        summary = "183 days of mostly kept habits, with misses, backfills and step gaps.",
        days = 183,
    )

    /**
     * Either side of first-run suppression (spec §5.5, scoring-cases 9.5-9.6).
     *
     * **"Days of history" here means days since install, today included.** The spec does not define
     * the count more precisely than that; M10 settles it in the dashboard, and if it counts
     * differently these two move with it. Loaded today, the 13-day one crosses the boundary tomorrow.
     */
    val Day13: Scenario = history(
        id = "day_13",
        summary = "13 days since install: every dashboard figure should still be suppressed.",
        days = 13,
    )

    val Day14: Scenario = history(
        id = "day_14",
        summary = "14 days since install: the dashboard's figures should appear.",
        days = 14,
    )

    val all: List<Scenario> = listOf(SixMonths, Day13, Day14)

    private fun history(id: String, summary: String, days: Int): Scenario = object : Scenario {
        override val id = id
        override val summary = summary
        override fun generate(clock: Clock) =
            // Seeded from the id, so each scenario is stable and no two share a history by accident.
            HistoryGenerator(clock, seed = id.hashCode()).generate(days)
    }
}
