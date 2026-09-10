package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.GoalResult
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.Target

/**
 * Scoring a measured day — steps, and in v1 only steps.
 *
 * **Separate from [GoalScorer] because a measured value is not an answer.** It has no `capture`, no
 * selections and no no-opportunity option, and it has a state those cannot have: a day the app
 * refuses to count. Routing it through the answer path would mean either inventing an `Answer` to
 * carry it — which would make a measured day indistinguishable from one the user typed in, exactly
 * what spec §2 forbids — or widening every branch of that function with cases only steps can reach.
 *
 * Attainment is produced here, unlike [GoalScorer.scoreValue]. A roll-up withholds it because the
 * denominator may be missing days; a single measured day has no such doubt, and "8,400 of 10,000"
 * is the figure spec §5.4 wants beside hit rate.
 */
object MeasuredScorer {

    /**
     * One measured day against one target.
     *
     * `null` is a day with no data — no record was written because none was read, which is not the
     * same as zero steps and must never be scored as a miss.
     *
     * **[MeasuredState.CONFLICTED] is excluded, not missed.** The user walked whatever they walked;
     * a day the app cannot count honestly is the app's problem, and charging them for it would turn
     * a source-configuration accident into a broken run.
     *
     * [MeasuredState.PROVISIONAL] scores exactly like [MeasuredState.FROZEN]. O4 makes a value
     * *revisable* for 24 hours, not unusable — withholding a score until it froze would leave every
     * day blank until the next rollover, which is a worse lie than a figure that may improve.
     */
    fun score(target: Target, value: MeasuredValue?): GoalResult {
        if (value == null) return GoalResult.excluded(ExclusionReason.NO_ANSWER)
        if (value.state == MeasuredState.CONFLICTED) {
            return GoalResult.excluded(ExclusionReason.SOURCE_CONFLICT)
        }

        val required = target.valueNumber
            ?: return GoalResult.excluded(ExclusionReason.NOT_SCORABLE)

        val met = when (target.direction) {
            Direction.AT_LEAST -> value.value >= required
            Direction.AT_MOST -> value.value <= required
            Direction.EXACTLY -> value.value == required
            else -> return GoalResult.excluded(ExclusionReason.NOT_SCORABLE)
        }

        return GoalResult(
            outcome = if (met) GoalOutcome.MET else GoalOutcome.MISSED,
            attainment = attainment(target, value.value),
        )
    }

    /**
     * The weekly figure: the sum of the days that carried one.
     *
     * Conflicted and missing days contribute nothing rather than zero. That understates a week
     * containing one — which is the right direction to be wrong in, since the alternative is
     * inventing steps — and the day's own exclusion is what makes the gap visible.
     */
    fun scoreWeek(target: Target, values: List<MeasuredValue>): GoalResult {
        val usable = values.filter { it.state != MeasuredState.CONFLICTED }
        if (usable.isEmpty()) return GoalResult.excluded(ExclusionReason.NO_ANSWER)

        val total = usable.sumOf { it.value }
        val required = target.valueNumber
            ?: return GoalResult.excluded(ExclusionReason.NOT_SCORABLE)

        val met = when (target.direction) {
            Direction.AT_LEAST -> total >= required
            Direction.AT_MOST -> total <= required
            Direction.EXACTLY -> total == required
            else -> return GoalResult.excluded(ExclusionReason.NOT_SCORABLE)
        }

        return GoalResult(
            outcome = if (met) GoalOutcome.MET else GoalOutcome.MISSED,
            attainment = attainment(target, total),
        )
    }

    /** Only a lower bound has a coherent "how close was I" — see [GoalScorer] for the reasoning. */
    private fun attainment(target: Target, achieved: Double): Double? {
        if (target.direction != Direction.AT_LEAST) return null
        val required = target.valueNumber ?: return null
        if (required <= 0.0) return null
        return (achieved / required).coerceIn(0.0, 1.0)
    }
}
