package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.item
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which view each item gets — spec §5.4, agreed one drawing at a time over three rounds.
 *
 * The rule reads the item rather than its name, so an item added later gets a considered view instead
 * of falling off the end of a list of sixteen. That only works if the rule genuinely reproduces all
 * sixteen agreed answers, which is what the first test is for.
 */
@DisplayName("Choosing an item's view - spec 5.4")
class ItemViewTest {

    private data class Seeded(
        val name: String,
        val kind: ItemKind,
        val version: ItemVersion,
        val targets: List<Target>,
        val expected: ItemView,
    )

    private fun seeded(
        name: String,
        answerType: AnswerType,
        slot: Slot,
        expected: ItemView,
        classification: Classification = Classification.GOAL,
        kind: ItemKind = ItemKind.ASKED,
        targets: List<Target> = emptyList(),
    ) = Seeded(name, kind, version(name, answerType, slot, classification), targets, expected)

    /** The seeded library, as data/.../SeedLibrary.kt defines it. */
    private val library = listOf(
        seeded("bedtime", AnswerType.TIME, Slot.MORNING, ItemView.ClockDots, Classification.OBSERVATION),
        seeded("woke_at", AnswerType.TIME, Slot.MORNING, ItemView.ClockDots, Classification.OBSERVATION),
        seeded("got_up_at", AnswerType.TIME, Slot.MORNING, ItemView.ClockDots, Classification.OBSERVATION),
        seeded(
            "pre_sleep", AnswerType.MULTI_SELECT, Slot.MORNING,
            ItemView.ActivityRows(OptionId("pre_sleep.scrolled_on_phone")),
            targets = listOf(
                target("pre_sleep", Direction.MUST_NOT_INCLUDE, option = "pre_sleep.scrolled_on_phone"),
            ),
        ),
        seeded(
            "meals", AnswerType.NUMBER, Slot.NIGHT,
            ItemView.ShadedCalendar(ShadeScale.targetAnchored(3.0)),
            targets = listOf(target("meals", Direction.AT_LEAST, value = 3.0)),
        ),
        seeded(
            "vitamins", AnswerType.BOOL, Slot.NIGHT, ItemView.DayCalendar(weeklyCount = false),
            targets = listOf(target("vitamins", Direction.IS_TRUE)),
        ),
        seeded(
            "water", AnswerType.NUMBER, Slot.NIGHT,
            ItemView.ShadedCalendar(ShadeScale.targetAnchored(2.0)),
            targets = listOf(target("water", Direction.AT_LEAST, value = 2.0)),
        ),
        seeded(
            "worked_out", AnswerType.BOOL, Slot.NIGHT, ItemView.DayCalendar(weeklyCount = true),
            targets = listOf(target("worked_out", Direction.AT_LEAST, value = 3.0, period = Period.WEEK)),
        ),
        seeded(
            "stretched", AnswerType.BOOL, Slot.NIGHT, ItemView.DayCalendar(weeklyCount = true),
            targets = listOf(target("stretched", Direction.AT_LEAST, value = 6.0, period = Period.WEEK)),
        ),
        seeded(
            "coffee", AnswerType.NUMBER, Slot.NIGHT, ItemView.DailyBars(weeklyTotals = true),
            targets = listOf(
                target("coffee", Direction.AT_MOST, value = 2.0),
                target("coffee", Direction.AT_MOST, value = 14.0, period = Period.WEEK),
            ),
        ),
        seeded(
            "took_time", AnswerType.SINGLE_SELECT, Slot.NIGHT, ItemView.DayCalendar(weeklyCount = false),
            targets = listOf(target("took_time", Direction.MUST_INCLUDE, option = "took_time.yes")),
        ),
        seeded(
            "mindset", AnswerType.SCALE, Slot.NIGHT,
            ItemView.ShadedCalendar(ShadeScale.perValue(ShadeScale.SCALE_RANGE)),
            Classification.OBSERVATION,
        ),
        seeded(
            "saw_friends", AnswerType.SINGLE_SELECT, Slot.WEEKLY, ItemView.WeekSquares,
            targets = listOf(target("saw_friends", Direction.MUST_INCLUDE, option = "saw_friends.yes", period = Period.WEEK)),
        ),
        seeded(
            "did_something_fun", AnswerType.SINGLE_SELECT, Slot.WEEKLY, ItemView.WeekSquares,
            targets = listOf(target("did_something_fun", Direction.MUST_INCLUDE, option = "did_something_fun.yes", period = Period.WEEK)),
        ),
        seeded(
            "invited_someone", AnswerType.SINGLE_SELECT, Slot.WEEKLY, ItemView.WeekSquares,
            targets = listOf(target("invited_someone", Direction.MUST_INCLUDE, option = "invited_someone.yes", period = Period.WEEK)),
        ),
        seeded(
            "steps", AnswerType.NUMBER, Slot.NONE, ItemView.StepBars, kind = ItemKind.MEASURED,
            targets = listOf(
                target("steps", Direction.AT_LEAST, value = 8_000.0),
                target("steps", Direction.AT_LEAST, value = 56_000.0, period = Period.WEEK),
            ),
        ),
    )

    @Test
    @DisplayName("5.4 - every seeded item gets the view it was agreed to have")
    fun theWholeLibrary() {
        assertEquals(16, library.size, "the seeded library has 16 items; this table must cover all of them")

        for (seeded in library) {
            assertEquals(
                seeded.expected,
                ItemViews.derive(seeded.kind, seeded.version, seeded.targets),
                seeded.name,
            )
        }
    }

    @Test
    @DisplayName("measured is decided before answer type, or steps would read as coffee")
    fun measuredWinsOverNumber() {
        // Steps is a number with a weekly target, which is coffee's shape exactly. What separates them
        // is that nobody answers steps.
        val stepsShape = version("steps", AnswerType.NUMBER, Slot.NONE)
        val weeklyTarget = listOf(target("steps", Direction.AT_LEAST, value = 56_000.0, period = Period.WEEK))

        assertEquals(ItemView.StepBars, ItemViews.derive(ItemKind.MEASURED, stepsShape, weeklyTarget))
        assertEquals(
            ItemView.DailyBars(weeklyTotals = true),
            ItemViews.derive(ItemKind.ASKED, stepsShape, weeklyTarget),
        )
    }

    @Test
    @DisplayName("a weekly question is decided before its answer type, or it would get a day calendar")
    fun weeklyWinsOverSelect() {
        val asked = version("saw_friends", AnswerType.SINGLE_SELECT, Slot.WEEKLY)
        assertEquals(ItemView.WeekSquares, ItemViews.derive(ItemKind.ASKED, asked, emptyList()))

        val nightly = version("saw_friends", AnswerType.SINGLE_SELECT, Slot.NIGHT)
        assertEquals(ItemView.DayCalendar(weeklyCount = false), ItemViews.derive(ItemKind.ASKED, nightly, emptyList()))
    }

    @Test
    @DisplayName("a count with no daily floor falls back to bars rather than inventing shades")
    fun numbersWithoutAFloor() {
        // An upper bound has no "how close did I get" to shade, and a number with no target at all has
        // nothing to anchor shades on.
        val v = version("cigarettes", AnswerType.NUMBER, Slot.NIGHT)

        assertEquals(
            ItemView.DailyBars(weeklyTotals = false),
            ItemViews.derive(ItemKind.ASKED, v, listOf(target("cigarettes", Direction.AT_MOST, value = 0.0))),
        )
        assertEquals(ItemView.DailyBars(weeklyTotals = false), ItemViews.derive(ItemKind.ASKED, v, emptyList()))
    }

    @Test
    @DisplayName("a multi-select with nothing forbidden flags no row")
    fun multiSelectWithoutAnAntiGoal() {
        val v = version("evening", AnswerType.MULTI_SELECT, Slot.NIGHT)

        assertEquals(ItemView.ActivityRows(flaggedOption = null), ItemViews.derive(ItemKind.ASKED, v, emptyList()))
    }

    @Test
    @DisplayName("an observation is drawn like anything else — classification only changes the figures")
    fun classificationDoesNotChangeTheView() {
        val goal = version("mood", AnswerType.SCALE, Slot.NIGHT, Classification.GOAL)
        val observation = version("mood", AnswerType.SCALE, Slot.NIGHT, Classification.OBSERVATION)

        assertEquals(
            ItemViews.derive(ItemKind.ASKED, goal, emptyList()),
            ItemViews.derive(ItemKind.ASKED, observation, emptyList()),
        )
    }
}
