package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.time.DayResolver

/**
 * The primary metric (spec section 1): the proportion of expected check-ins that were answered.
 *
 * **Computed from check-ins alone, never from answers.** That is not an implementation detail: it is
 * what stops a LATE answer from repairing a missed check-in (A1.2) and what makes filling in a whole
 * week on Sunday still report 0% for that week (A1.5). Unlimited backfill that repaired the metric
 * was rejected in round 1 as making the record fiction. The data is worth having; the metric is not
 * for sale.
 *
 * [rate] and [inWindowOnlyRate] are null when nothing was expected -- nothing to report, not zero.
 */
data class ResponseRate(
    val expected: Int,
    val answered: Int,
    val answeredInWindow: Int,
) {
    /** The headline figure. Backfill within grace counts as answered. */
    val rate: Double? get() = if (expected == 0) null else answered.toDouble() / expected

    /**
     * The stricter figure, counting only check-ins answered on the day they belong to. Spec 3.2
     * requires this to stay recoverable, so both are computable from the same rows.
     */
    val inWindowOnlyRate: Double? get() = if (expected == 0) null else answeredInWindow.toDouble() / expected

    companion object {
        fun of(checkIns: List<CheckIn>, dayResolver: DayResolver): ResponseRate {
            val answered = checkIns.filter { it.state == CheckInState.ANSWERED }
            return ResponseRate(
                expected = checkIns.size,
                answered = answered.size,
                answeredInWindow = answered.count { checkIn ->
                    // In window means answered on the day the check-in itself belongs to. Anything
                    // later is a backfill, which still counts as answered but not as in-window.
                    checkIn.answeredAt?.let { dayResolver.dayFor(it) == checkIn.day } == true
                },
            )
        }
    }
}
