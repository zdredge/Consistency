package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.Target

/**
 * Evaluates one answer against one target and returns a three-state outcome.
 *
 * The order of the checks matters and is not an implementation detail:
 *
 * 1. **No answer at all is EXCLUDED, before any direction is considered** (scoring-cases 1.13, spec
 *    constraint 11). This is the single most likely place for the scoring to be implemented wrong:
 *    a naive "the forbidden option is not present" test returns true for a missing answer, quietly
 *    turning every skipped day into a success and inflating goal completion.
 * 2. Only then is the direction applied.
 *
 * An answer whose relevant value is absent -- a numeric direction with no number, say -- is likewise
 * EXCLUDED rather than guessed at. There is nothing to score, and inventing a comparison would
 * produce exactly the plausible-but-wrong number this module exists to avoid.
 */
object DirectionEvaluator {

    fun evaluate(target: Target, answer: Answer?): GoalOutcome {
        // Rule 1: silence. Excluded from both numerator and denominator -- never met, never missed.
        if (answer == null) return GoalOutcome.EXCLUDED

        return when (target.direction) {
            Direction.AT_LEAST -> compareNumbers(target, answer) { a, t -> a >= t }
            Direction.AT_MOST -> compareNumbers(target, answer) { a, t -> a <= t }
            Direction.EXACTLY -> compareNumbers(target, answer) { a, t -> a == t }
            Direction.IS_TRUE -> checkBoolean(answer, expected = true)
            Direction.IS_FALSE -> checkBoolean(answer, expected = false)
            Direction.MUST_INCLUDE -> checkSelection(target, answer, shouldContain = true)
            Direction.MUST_NOT_INCLUDE -> checkSelection(target, answer, shouldContain = false)
        }
    }

    private inline fun compareNumbers(
        target: Target,
        answer: Answer,
        compare: (answer: Double, target: Double) -> Boolean,
    ): GoalOutcome {
        val answered = answer.valueNumber ?: return GoalOutcome.EXCLUDED
        val required = target.valueNumber ?: return GoalOutcome.EXCLUDED
        return if (compare(answered, required)) GoalOutcome.MET else GoalOutcome.MISSED
    }

    private fun checkBoolean(answer: Answer, expected: Boolean): GoalOutcome {
        val answered = answer.valueBool ?: return GoalOutcome.EXCLUDED
        return if (answered == expected) GoalOutcome.MET else GoalOutcome.MISSED
    }

    private fun checkSelection(target: Target, answer: Answer, shouldContain: Boolean): GoalOutcome {
        val option = target.optionId ?: return GoalOutcome.EXCLUDED
        // Reached only when an answer exists: an empty selection set is a real answer of "none of
        // these", which is a legitimate way to satisfy MUST_NOT_INCLUDE. Silence was handled above.
        return if (answer.selections.contains(option) == shouldContain) {
            GoalOutcome.MET
        } else {
            GoalOutcome.MISSED
        }
    }
}
