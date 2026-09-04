package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.GoalResult
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.Target
import java.time.LocalDate

/**
 * A weekly figure derived from a daily item, plus whether the week can be trusted to be whole.
 *
 * [incomplete] is not decoration. Spec 3.4: a week containing unanswered days is labelled incomplete
 * **wherever the figure appears**, because four workouts across a week with two blank days is four,
 * not four-of-five. The blanks might each have been a workout; the app does not guess in either
 * direction, it says so.
 */
data class RollUp(
    val value: Double,
    val observedDays: Int,
    val expectedDays: Int,
) {
    val incomplete: Boolean get() = observedDays < expectedDays
}

/**
 * Roll-ups are explicit, not inferred (spec 3.4): an item declares a source and an aggregation.
 * Derived weekly figures must be labelled as derived, with their source visible.
 */
object RollUpCalculator {

    /**
     * Folds the [answers] observed within [daysInPeriod]. **Counts observed values only** -- absent
     * days contribute nothing rather than a zero, which is the difference between "four workouts"
     * and "four out of five".
     */
    fun weekly(
        answers: List<Answer>,
        daysInPeriod: List<LocalDate>,
        aggregation: RollUpAggregation,
    ): RollUp {
        val days = daysInPeriod.toSet()
        val observed = answers.filter { it.day in days }

        val value = when (aggregation) {
            RollUpAggregation.COUNT_OF_YES -> observed.count { it.valueBool == true }.toDouble()
            RollUpAggregation.SUM -> observed.sumOf { it.valueNumber ?: 0.0 }
            RollUpAggregation.AVERAGE -> observed.mapNotNull { it.valueNumber }
                .let { if (it.isEmpty()) 0.0 else it.average() }
            RollUpAggregation.MAX -> observed.mapNotNull { it.valueNumber }.maxOrNull() ?: 0.0
        }

        return RollUp(
            value = value,
            observedDays = observed.map { it.day }.distinct().size,
            expectedDays = daysInPeriod.size,
        )
    }

    /**
     * Scores a rolled-up figure against a weekly target. Assessed against **what was observed**: a
     * weekly "at least four" is met by four observed workouts even though two days are blank (A3.2),
     * and missed by three even though four days are blank (A3.4). The incompleteness travels with
     * the figure rather than changing the verdict.
     */
    fun score(rollUp: RollUp, target: Target): GoalResult =
        GoalScorer.scoreValue(target, rollUp.value)
}
