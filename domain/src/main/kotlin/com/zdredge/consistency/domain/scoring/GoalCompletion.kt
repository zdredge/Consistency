package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.GoalResult

/**
 * One granularity's goal completion: met instances over scored instances.
 *
 * Exclusions touch neither side, whatever their reason -- silence, no-opportunity, an inactive item
 * or an open period. [ratio] is null when nothing was scored, which means "nothing to report", never
 * "nothing achieved" (11.7).
 */
data class GoalCompletion(
    val met: Int,
    val missed: Int,
    val excluded: Int,
) {
    val scored: Int get() = met + missed

    val ratio: Double? get() = if (scored == 0) null else met.toDouble() / scored

    companion object {
        fun of(results: List<GoalResult>): GoalCompletion = GoalCompletion(
            met = results.count { it.outcome == GoalOutcome.MET },
            missed = results.count { it.outcome == GoalOutcome.MISSED },
            excluded = results.count { it.outcome == GoalOutcome.EXCLUDED },
        )
    }
}

/**
 * The two goal-completion figures, **kept apart** -- the O1 resolution and spec constraint 8.
 *
 * Each ring is instance-based *within* its own granularity. Pooling daily and weekly goals into one
 * ratio would silently drown the roughly two weekly instances per window under the roughly fourteen
 * daily ones: a hidden weighting, and one that makes a change in the score untraceable to any
 * behaviour.
 *
 * **There is deliberately no combined figure here, and none may be added.** A blended number cannot
 * distinguish not-answering from not-doing-daily from not-doing-weekly, which are three different
 * problems with three different remedies. Response rate is likewise kept separate again.
 */
data class GoalCompletionRings(
    val daily: GoalCompletion,
    val weekly: GoalCompletion,
)
