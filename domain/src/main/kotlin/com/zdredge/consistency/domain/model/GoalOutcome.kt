package com.zdredge.consistency.domain.model

/**
 * How one goal fared in one period. **Three states, never a Boolean.**
 *
 * This is the spine of the whole rulebook. Three different flavours of "no positive answer" must
 * stay distinguishable, and a Boolean cannot hold them apart:
 *
 * - **silence** (no answer at all) is [EXCLUDED], and it separately costs the check-in and so the
 *   response rate -- scoring-cases 1.13, spec constraint 11 "silence is not success";
 * - an **unresolved pending** ("not yet", never followed up) is [MISSED] -- scoring-cases A2.1, the
 *   only case in the rulebook where an absent value scores as missed;
 * - a **no-opportunity** answer is [EXCLUDED] and costs nothing, because the user actively said the
 *   period did not allow it -- scoring-cases 10.3, spec constraint 17.
 *
 * Collapsing these loses real information and, in the 1.13 direction, silently inflates goal
 * completion on exactly the days the user skipped. Do not reduce this to a Boolean, and do not
 * treat [EXCLUDED] as a miss.
 */
enum class GoalOutcome {
    /** The target was met. Counts in both numerator and denominator. */
    MET,

    /** The target was not met. Counts in the denominator only. */
    MISSED,

    /**
     * Not scored at all -- absent from numerator *and* denominator. Silence, a no-opportunity
     * answer, or an item that was not active in the period. Never a miss.
     */
    EXCLUDED,
}

/**
 * The result of scoring one goal in one period: the binary outcome plus, separately, how close the
 * user came.
 *
 * Spec constraint 16: attainment is reported **alongside** hit rate, never merged into it and never
 * dropped. Scoring stays binary (partial credit was deliberately rejected -- it lets someone sit at
 * a comfortable 75% forever), while attainment answers the different question of how close.
 *
 * [attainment] is null, not zero, when the question is meaningless: at-most directions have no
 * sensible "how close" (scoring-cases 2.5), and neither do non-numeric goals. Asserting absence
 * rather than zero is deliberate -- zero would read as total failure.
 */
data class GoalResult(
    val outcome: GoalOutcome,
    /** 0.0-1.0, capped at 1.0 (scoring-cases 2.4). Null where attainment has no meaning. */
    val attainment: Double? = null,
) {
    init {
        require(attainment == null || attainment in 0.0..1.0) {
            "attainment must be a 0.0-1.0 fraction or null, was $attainment"
        }
    }

    companion object {
        val EXCLUDED = GoalResult(GoalOutcome.EXCLUDED)
    }
}
