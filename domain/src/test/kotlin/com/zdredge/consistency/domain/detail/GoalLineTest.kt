package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.rollUp
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every seeded goal's line, exactly as the user approved it in the M10 review (round 4).
 *
 * A wording change here is a change to what the user reads on every panel and item screen, so the
 * expected strings are the approved ones rather than whatever the generator happens to produce.
 */
@DisplayName("A goal in words")
class GoalLineTest {

    private fun yesNo(item: String) = listOf(
        SelectOption(OptionId("$item.yes"), ItemId(item), "Yes", 0),
        SelectOption(OptionId("$item.no"), ItemId(item), "No", 1),
        SelectOption(OptionId("$item.no_opportunity"), ItemId(item), "No opportunity", 2, isNoOpportunity = true),
    )

    @Test
    @DisplayName("M10 - the seeded goals read as approved")
    fun theSeededGoals() {
        val preSleep = listOf(
            SelectOption(OptionId("pre_sleep.read_a_book"), ItemId("pre_sleep"), "Read a book", 0),
            SelectOption(OptionId("pre_sleep.scrolled_on_phone"), ItemId("pre_sleep"), "Scrolled on phone", 2),
        )
        val cases = listOf(
            GoalLine.of(target("meals", Direction.AT_LEAST, 3.0), version("meals", AnswerType.NUMBER), emptyList(), null)
                to "at least 3 a day",
            GoalLine.of(target("vitamins", Direction.IS_TRUE), version("vitamins", AnswerType.BOOL), emptyList(), null)
                to "yes, every day",
            GoalLine.of(target("water", Direction.AT_LEAST, 2.0), version("water", AnswerType.NUMBER, unitLabel = "bottles"), emptyList(), null)
                to "at least 2 bottles a day",
            GoalLine.of(
                target("worked_out", Direction.AT_LEAST, 3.0, period = Period.WEEK), version("worked_out", AnswerType.BOOL),
                emptyList(), rollUp("worked_out", RollUpAggregation.COUNT_OF_YES),
            ) to "yes on at least 3 days a week",
            GoalLine.of(
                target("stretched", Direction.AT_LEAST, 6.0, period = Period.WEEK), version("stretched", AnswerType.BOOL),
                emptyList(), rollUp("stretched", RollUpAggregation.COUNT_OF_YES),
            ) to "yes on at least 6 days a week",
            GoalLine.of(target("coffee", Direction.AT_MOST, 2.0), version("coffee", AnswerType.NUMBER), emptyList(), null)
                to "no more than 2 a day",
            GoalLine.of(
                target("took_time", Direction.MUST_INCLUDE, option = "took_time.yes"),
                version("took_time", AnswerType.SINGLE_SELECT), yesNo("took_time"), null,
            ) to "“Yes”, every day",
            GoalLine.of(
                target("pre_sleep", Direction.MUST_NOT_INCLUDE, option = "pre_sleep.scrolled_on_phone"),
                version("pre_sleep", AnswerType.MULTI_SELECT, slot = Slot.MORNING), preSleep, null,
            ) to "not “Scrolled on phone”, every night",
            GoalLine.of(target("steps", Direction.AT_LEAST, 8_000.0), version("steps", AnswerType.NUMBER, slot = Slot.NONE), emptyList(), null)
                to "at least 8,000 a day",
            GoalLine.of(
                target("saw_friends", Direction.MUST_INCLUDE, option = "saw_friends.yes", period = Period.WEEK),
                version("saw_friends", AnswerType.SINGLE_SELECT, slot = Slot.WEEKLY), yesNo("saw_friends"), null,
            ) to "“Yes”, every week",
        )
        cases.forEach { (actual, expected) -> assertEquals(expected, actual) }
    }

    @Test
    @DisplayName("M10 - a weekly sum is a plain amount, not a count of days")
    fun aWeeklySum() {
        val line = GoalLine.of(
            target("coffee", Direction.AT_MOST, 14.0, period = Period.WEEK), version("coffee", AnswerType.NUMBER),
            emptyList(), rollUp("coffee", RollUpAggregation.SUM),
        )
        assertEquals("no more than 14 a week", line)
    }

    @Test
    @DisplayName("M10 - a half keeps its decimal")
    fun aFractionalTarget() {
        val line = GoalLine.of(target("water", Direction.AT_LEAST, 2.5), version("water", AnswerType.NUMBER, unitLabel = "Bottles"), emptyList(), null)
        assertEquals("at least 2.5 bottles a day", line)
    }

    @Test
    @DisplayName("M10 - the question loses its trailing unit, and nothing else")
    fun theQuestionHeading() {
        assertEquals("How much water did you drink?", GoalLine.question("How much water did you drink? (Bottles)"))
        assertEquals("Did you take time when time could be taken?", GoalLine.question("Did you take time when time could be taken?"))
        assertEquals("Steps", GoalLine.question("Steps"))
    }
}
