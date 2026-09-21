package com.zdredge.consistency.fixture

import com.zdredge.consistency.domain.model.ContainerSize
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.time.DayResolver
import java.time.Clock
import java.time.DayOfWeek

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
    val SixMonths: Scenario = scenario(
        id = "six_months",
        summary = "183 days of mostly kept habits, with misses, backfills and step gaps.",
    ) { HistoryShape(days = 183) }

    /**
     * Either side of first-run suppression (spec §5.5, scoring-cases 9.5-9.6).
     *
     * **"Days of history" here means days since install, today included.** The spec does not define
     * the count more precisely than that; M10 settles it in the dashboard, and if it counts
     * differently these two move with it. Loaded today, the 13-day one crosses the boundary tomorrow.
     */
    val Day13: Scenario = scenario(
        id = "day_13",
        summary = "13 days since install: every dashboard figure should still be suppressed.",
    ) { HistoryShape(days = 13) }

    val Day14: Scenario = scenario(
        id = "day_14",
        summary = "14 days since install: the dashboard's figures should appear.",
    ) { HistoryShape(days = 14) }

    /**
     * Every capture state (spec §3.2): in-window, backfilled, late, and "not yet" -- resolved the next
     * morning, left unresolved past its grace, and one still open.
     */
    val Captures: Scenario = scenario(
        id = "captures",
        summary = "Backfilled and late answers, and deferrals resolved, unresolved and still open.",
    ) {
        HistoryShape(
            days = 28,
            backfillRate = 0.25,
            deferredNights = { it in CAPTURES_DEFERRED },
            // The morning after night 9 goes unanswered, so that deferral is never resolved; the
            // morning after night 26 is today's, left open, so that one still can be.
            unanswered = { off, slot -> slot == Slot.MORNING && off in setOf(10L, 27L) },
            answered = { off, slot -> slot == Slot.MORNING && off - 1 in CAPTURES_DEFERRED && off !in setOf(10L, 27L) },
            lateNights = { it in setOf(6L, 13L) },
        )
    }

    /** Answers corrected a day after they were given (spec §3.2: edits set the edited flag). */
    val Edited: Scenario = scenario(
        id = "edited",
        summary = "Answers corrected a day later: edit stamps set, original capture kept.",
    ) {
        HistoryShape(
            days = 28,
            corrected = { off, slot ->
                (slot == Slot.NIGHT && off % 4 == 1L) || (slot == Slot.MORNING && off % 7 == 3L)
            },
        )
    }

    /**
     * An item retired part-way through, and a question reworded (spec §3.3, scoring-cases 6.x).
     *
     * Stretched retires on day 40, so it must show only in the periods it was active in. Meals is
     * reworded on day 50, so its earlier answers stay pinned to the version they answered.
     *
     * **Both fall inside the item screen's five weeks.** That screen shows nothing older, so a change
     * made earlier than about 34 days ago could be proven by the tests and never seen. Found on the
     * device, where water's day-35 change sat just outside the window.
     */
    val RetiredReversioned: Scenario = scenario(
        id = "retired_reversioned",
        summary = "Stretched retired 29 days ago; the meals question reworded 19 days ago.",
    ) {
        HistoryShape(
            days = 70,
            library = { library, installDay ->
                val meals = library.versions.single { it.itemId == MEALS }
                library.copy(
                    items = library.items.map {
                        if (it.id == STRETCHED) it.copy(retiredOn = installDay.plusDays(RETIRED_ON)) else it
                    },
                    versions = library.versions + meals.copy(
                        id = ItemVersionId("meals.v2"),
                        versionNo = 2,
                        prompt = "How many proper meals did you eat?",
                        effectiveFrom = installDay.plusDays(REVERSIONED_ON),
                    ),
                )
            },
        )
    }

    /** Days a second app also reported steps, which the origin guard flags (architecture §8). */
    val MultiOriginSteps: Scenario = scenario(
        id = "multi_origin_steps",
        summary = "Five step days reported by two apps, flagged conflicted and left unscored.",
    ) { HistoryShape(days = 28, stepGapRate = 0.0, secondStepOrigin = { it in setOf(5L, 12L, 13L, 20L, 26L) }) }

    /** A silent week, a half-silent one and scattered silent days, with steps missing on some. */
    val GappyWeeks: Scenario = scenario(
        id = "gappy_weeks",
        summary = "A whole week unanswered, two more with gaps, and days with no steps.",
    ) {
        HistoryShape(
            days = 56,
            unanswered = { off, _ -> off in GAPPY_SILENT },
            noSteps = { it in setOf(15L, 16L, 17L, 33L) },
        )
    }

    /**
     * A target and a container size changed part-way through (spec constraint 2, scoring-cases 5.4).
     * Water's daily target rises from 2 bottles to 3 on day 55, and the bottle shrinks from 40 oz to
     * 32 oz the same day -- inside the item screen's five weeks, so both targets are on it. Earlier days must keep scoring against the old values.
     */
    val EffectiveFrom: Scenario = scenario(
        id = "effective_from",
        summary = "Water's target raised to 3 bottles and its bottle shrunk to 32 oz 14 days ago.",
    ) {
        HistoryShape(
            days = 70,
            library = { library, installDay ->
                val changedOn = installDay.plusDays(CHANGED_ON)
                library.copy(
                    targets = library.targets + Target(WATER, Period.DAY, Direction.AT_LEAST, 3.0, effectiveFrom = changedOn),
                    containerSizes = library.containerSizes + ContainerSize(WATER, 32.0, "oz", changedOn),
                )
            },
        )
    }

    /**
     * Four weeks in a row where seeing friends had no opportunity (spec constraint 17): excluded
     * every time, never a miss, and never a break in the run.
     */
    val NoOpportunity: Scenario = object : Scenario {
        override val id = "no_opportunity"
        override val summary = "Four Sundays in a row answering \"no opportunity\" to seeing friends."

        override fun generate(clock: Clock): FixtureDataset {
            val installDay = DayResolver(clock).today().minusDays(NO_OPPORTUNITY_DAYS - 1L)
            val sundays = (7L..34L).filter { installDay.plusDays(it).dayOfWeek == DayOfWeek.SUNDAY }.toSet()
            return HistoryGenerator(clock, seed = id.hashCode()).generate(
                HistoryShape(
                    days = NO_OPPORTUNITY_DAYS,
                    answered = { off, slot -> slot == Slot.NIGHT && off in sundays },
                    value = { answer, off ->
                        if (answer.itemId == SAW_FRIENDS && off in sundays) {
                            answer.copy(selections = setOf(OptionId("saw_friends.no_opportunity")))
                        } else {
                            answer
                        }
                    },
                ),
            )
        }
    }

    val all: List<Scenario> = listOf(
        SixMonths, Day13, Day14, Captures, Edited, RetiredReversioned, MultiOriginSteps, GappyWeeks,
        EffectiveFrom, NoOpportunity,
    )

    internal val MEALS = ItemId("meals")
    internal val STRETCHED = ItemId("stretched")
    internal val WATER = ItemId("water")
    internal val SAW_FRIENDS = ItemId("saw_friends")

    internal const val RETIRED_ON = 40L
    internal const val REVERSIONED_ON = 50L
    internal const val CHANGED_ON = 55L
    internal const val NO_OPPORTUNITY_DAYS = 42
    internal val CAPTURES_DEFERRED = setOf(4L, 9L, 15L, 20L, 26L)
    internal val GAPPY_SILENT = (14L..20L).toSet() + setOf(30L, 33L, 44L)

    private fun scenario(id: String, summary: String, shape: () -> HistoryShape): Scenario = object : Scenario {
        override val id = id
        override val summary = summary
        override fun generate(clock: Clock) =
            // Seeded from the id, so each scenario is stable and no two share a history by accident.
            HistoryGenerator(clock, seed = id.hashCode()).generate(shape())
    }
}
