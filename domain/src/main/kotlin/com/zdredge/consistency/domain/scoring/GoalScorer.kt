package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.OptionId
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

    /**
     * The order of these checks is part of the rulebook, not an implementation detail.
     *
     * 1. **Silence** -- no answer at all -- is excluded (1.13, constraint 11).
     * 2. **An unresolved deferral** is MISSED (A2.1). This is the rulebook's only absent-value miss,
     *    and it sits above the no-opportunity check because a "not yet" carries no selection to test.
     * 3. **A no-opportunity answer** is excluded **before direction is considered** (10.7), so the
     *    comparator never sees it and the target it happens to hold is irrelevant.
     * 4. Only then does the direction apply.
     */
    fun score(
        target: Target,
        answer: Answer?,
        noOpportunityOptions: Set<OptionId> = emptySet(),
    ): GoalResult {
        if (answer == null) return GoalResult.excluded(ExclusionReason.NO_ANSWER)

        // A deferral that was never followed up. Whatever value the row happens to carry is stale:
        // the user actively chose not to answer yet, and then did not come back.
        if (answer.capture == Capture.PENDING) return GoalResult(GoalOutcome.MISSED)

        if (answer.selections.any { it in noOpportunityOptions }) {
            return GoalResult.excluded(ExclusionReason.NO_OPPORTUNITY)
        }

        return when (val outcome = DirectionEvaluator.evaluate(target, answer)) {
            // The direction could not compare anything -- an answer with no usable value.
            GoalOutcome.EXCLUDED -> GoalResult.excluded(ExclusionReason.NOT_SCORABLE)
            else -> GoalResult(outcome = outcome, attainment = attainment(target, answer))
        }
    }

    /**
     * How close the answer came, as a 0.0-1.0 fraction, or null where the question is meaningless.
     *
     * Only **AT_LEAST** has a sensible "how close was I": it has a floor to fall short of. For an
     * upper bound the question is incoherent -- 3 coffees against a limit of 2 is neither 150% nor
     * 67% of anything (scoring-cases 2.5) -- and reporting zero would read as total failure, which is
     * exactly the misreading attainment exists to prevent. Absence is the honest representation.
     *
     * EXACTLY is also null. The question came up over meals and was answered by fixing the target
     * rather than the arithmetic: an extra meal is not a failure, so meals is "at least 3", not
     * "exactly 3" (spec section 4). **No seed goal currently uses EXACTLY**, so this branch is moot
     * in practice. Were one ever added, measuring distance from target -- 1 - |actual - target| /
     * target, giving 2 and 4 the same 67% -- would beat a capped ratio, which would otherwise report
     * a missed overshoot as 100%.
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
    /**
     * How many exclusions were the user actively saying the period did not allow it. Constraint 17
     * requires this stays visible, so that leaning on the neutral option is legible rather than
     * hidden -- the same principle as a LATE answer keeping the data without repairing the metric.
     */
    val noOpportunityCount: Int,
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
                noOpportunityCount = results.count {
                    it.exclusionReason == ExclusionReason.NO_OPPORTUNITY
                },
            )
        }
    }
}
