package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.GoalResult
import com.zdredge.consistency.domain.model.Target

/**
 * Scores one answer against one target, producing the binary outcome and, separately, attainment.
 *
 * Spec constraint 16: the two are reported alongside each other, never merged and never dropped.
 * Scoring is binary because proportional credit lets someone sit at a comfortable 75% forever
 * without ever hitting a target; attainment exists because a binary number alone turns a fortnight
 * of 1.5-of-2 bottles into apparent total failure. They answer different questions.
 */
object GoalScorer {

    fun score(target: Target, answer: Answer?): GoalResult {
        val outcome = DirectionEvaluator.evaluate(target, answer)
        return GoalResult(
            outcome = outcome,
            // An excluded goal was not scored, so there is nothing to be close to.
            attainment = if (outcome == GoalOutcome.EXCLUDED) null else attainment(target, answer),
        )
    }

    /**
     * How close the answer came, as a 0.0-1.0 fraction, or null where the question is meaningless.
     *
     * Only **AT_LEAST** has a sensible "how close was I": it has a floor to fall short of. For an
     * upper bound the question is incoherent -- 3 coffees against a limit of 2 is neither 150% nor
     * 67% of anything (scoring-cases 2.5) -- and reporting zero would read as total failure, which is
     * exactly the misreading attainment exists to prevent. Absence is the honest representation.
     *
     * EXACTLY is also null, and that is a judgement call rather than a documented rule: undershooting
     * 3 meals with 2 arguably *is* 67% of the way, but overshooting with 4 would report as capped
     * 100% attainment on a missed goal, which is worse than saying nothing. Revisit if a real
     * EXACTLY goal ever wants it.
     */
    private fun attainment(target: Target, answer: Answer?): Double? {
        if (target.direction != Direction.AT_LEAST) return null
        val achieved = answer?.valueNumber ?: return null
        val required = target.valueNumber ?: return null
        if (required <= 0.0) return null
        return (achieved / required).coerceIn(0.0, 1.0)
    }
}

/**
 * One item's figures over a set of scored periods -- the per-item half of the dashboard.
 *
 * [hitRate] and [averageAttainment] are both nullable and both mean "there is nothing to report",
 * never "zero". An item with no scored instances must not land in the going-badly panel it never
 * earned (see scoring-cases 10.5 and 11.7).
 */
data class ItemSummary(
    val met: Int,
    val missed: Int,
    val excluded: Int,
    /** met / (met + missed). Null when nothing was scored. Exclusions touch neither side. */
    val hitRate: Double?,
    /** Mean of the attainments that exist. Null when none do. */
    val averageAttainment: Double?,
) {
    companion object {
        fun of(results: List<GoalResult>): ItemSummary {
            val met = results.count { it.outcome == GoalOutcome.MET }
            val missed = results.count { it.outcome == GoalOutcome.MISSED }
            val excluded = results.count { it.outcome == GoalOutcome.EXCLUDED }
            val scored = met + missed

            val attainments = results.mapNotNull { it.attainment }

            return ItemSummary(
                met = met,
                missed = missed,
                excluded = excluded,
                hitRate = if (scored == 0) null else met.toDouble() / scored,
                averageAttainment = if (attainments.isEmpty()) null else attainments.average(),
            )
        }
    }
}
