package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

/**
 * docs/scoring-cases.md section 1. A target carries a direction, not just a value (spec constraint
 * 3): a bare number cannot express "at most two".
 */
@DisplayName("Direction evaluation - scoring-cases section 1")
class DirectionEvaluatorTest {

    @ParameterizedTest(name = "{0} - {1} {2}, answer {3} -> {4}")
    @CsvSource(
        "1.1, AT_LEAST, 2, 2, MET",
        "1.2, AT_LEAST, 2, 1, MISSED",
        "1.3, AT_MOST,  2, 2, MET",
        "1.4, AT_MOST,  2, 3, MISSED",
        "1.5, EXACTLY,  3, 3, MET",
        "1.6, EXACTLY,  3, 4, MISSED",
    )
    @DisplayName("1.1-1.6 - numeric directions compare against the target value")
    fun numericDirections(
        case: String,
        direction: Direction,
        targetValue: Double,
        answerValue: Double,
        expected: GoalOutcome,
    ) {
        val outcome = DirectionEvaluator.evaluate(
            target = target("numeric", direction, value = targetValue),
            answer = answer("numeric", number = answerValue),
        )
        assertEquals(expected, outcome, "case $case")
    }

    @ParameterizedTest(name = "{0} - {1}, answer {2} -> {3}")
    @CsvSource(
        "1.7, IS_TRUE,  true,  MET",
        "1.8, IS_TRUE,  false, MISSED",
        "1.9, IS_FALSE, false, MET",
    )
    @DisplayName("1.7-1.9 - boolean directions")
    fun booleanDirections(
        case: String,
        direction: Direction,
        answerValue: Boolean,
        expected: GoalOutcome,
    ) {
        val outcome = DirectionEvaluator.evaluate(
            target = target("vitamins", direction),
            answer = answer("vitamins", bool = answerValue),
        )
        assertEquals(expected, outcome, "case $case")
    }

    @Test
    @DisplayName("1.10 - MUST_INCLUDE is met when the option is among the selections")
    fun mustIncludePresent() {
        val outcome = DirectionEvaluator.evaluate(
            target = target("pre_sleep", Direction.MUST_INCLUDE, option = "read_a_book"),
            answer = answer("pre_sleep", selections = setOf("read_a_book", "watched_youtube")),
        )
        assertEquals(GoalOutcome.MET, outcome)
    }

    @Test
    @DisplayName("1.10b - MUST_INCLUDE is MISSED when the required option is absent")
    fun mustIncludeAbsent() {
        // Not in scoring-cases.md, which specifies only the positive case for MUST_INCLUDE. The
        // direction names ONE option (spec 3.4, "must include option"), so other selections being
        // present is irrelevant: watching YouTube does not satisfy "must include read a book".
        val outcome = DirectionEvaluator.evaluate(
            target = target("pre_sleep", Direction.MUST_INCLUDE, option = "read_a_book"),
            answer = answer("pre_sleep", selections = setOf("watched_youtube")),
        )
        assertEquals(GoalOutcome.MISSED, outcome)
    }

    @Test
    @DisplayName("1.10c - MUST_INCLUDE is MISSED when nothing at all was selected")
    fun mustIncludeEmptySelection() {
        // An answered "none of these" is a real answer, so it is a MISS, not an exclusion. Only the
        // complete absence of an answer is excluded (1.13).
        val outcome = DirectionEvaluator.evaluate(
            target = target("pre_sleep", Direction.MUST_INCLUDE, option = "read_a_book"),
            answer = answer("pre_sleep", selections = emptySet()),
        )
        assertEquals(GoalOutcome.MISSED, outcome)
    }

    @Test
    @DisplayName("1.11 - MUST_NOT_INCLUDE is met when the forbidden option is absent")
    fun mustNotIncludeAbsent() {
        val outcome = DirectionEvaluator.evaluate(
            target = target("pre_sleep", Direction.MUST_NOT_INCLUDE, option = "scrolled_on_phone"),
            answer = answer("pre_sleep", selections = setOf("read_a_book", "watched_youtube")),
        )
        assertEquals(GoalOutcome.MET, outcome)
    }

    @Test
    @DisplayName("1.12 - MUST_NOT_INCLUDE is missed when the forbidden option is present")
    fun mustNotIncludePresent() {
        val outcome = DirectionEvaluator.evaluate(
            target = target("pre_sleep", Direction.MUST_NOT_INCLUDE, option = "scrolled_on_phone"),
            answer = answer("pre_sleep", selections = setOf("scrolled_on_phone")),
        )
        assertEquals(GoalOutcome.MISSED, outcome)
    }

    @Test
    @DisplayName("1.13 - MUST_NOT_INCLUDE with NO answer is EXCLUDED: silence is not success")
    fun silenceIsNotSuccess() {
        // The highest-priority test in the document (spec constraint 11). A naive implementation of
        // "the forbidden option is not present" returns true for a missing answer and silently
        // inflates goal completion on exactly the days the user skipped.
        val outcome = DirectionEvaluator.evaluate(
            target = target("pre_sleep", Direction.MUST_NOT_INCLUDE, option = "scrolled_on_phone"),
            answer = null,
        )
        assertEquals(GoalOutcome.EXCLUDED, outcome)
        assertNotEquals(GoalOutcome.MET, outcome, "silence must never read as success")
        assertNotEquals(GoalOutcome.MISSED, outcome, "silence is excluded, not a miss")
    }

    @ParameterizedTest(name = "silence with {0} is EXCLUDED")
    @EnumSource(Direction::class)
    @DisplayName("1.13 generalised - silence is EXCLUDED whatever the direction")
    fun silenceIsExcludedForEveryDirection(direction: Direction) {
        val outcome = DirectionEvaluator.evaluate(
            target = target("any", direction, value = 1.0, option = "opt"),
            answer = null,
        )
        assertEquals(GoalOutcome.EXCLUDED, outcome)
    }
}
