package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** What one rollover should change. Plain data; `:data` writes it. */
data class RolloverPlan(
    /** Check-ins whose grace has closed unanswered. */
    val checkInsToMiss: List<CheckIn> = emptyList(),
    /** Measured values whose provisional window has elapsed (spec O4). */
    val valuesToFreeze: List<MeasuredValue> = emptyList(),
) {
    val isEmpty: Boolean get() = checkInsToMiss.isEmpty() && valuesToFreeze.isEmpty()
}

/**
 * What the 04:00 job changes that nothing else can.
 *
 * Architecture §6 calls the rollover "the only thing that writes without the user" and warns that if
 * it silently fails "the app looks fine and every number is subtly wrong". This is the part of it
 * that can be *wrong* rather than merely late, so it is pure and lives here: the worker reads state,
 * asks this what to change, and writes the answer.
 *
 * ### Two jobs, not four
 *
 * The rollover is often described as four tasks. Two of them are not this function's:
 *
 * - **Generating expected check-in rows** stays in `ConsistencyRepository.ensureCheckInsExist`, which
 *   already plans through `CheckInPlanner`. A second implementation of the response-rate denominator
 *   is the last thing this app needs.
 * - **Converting unresolved deferrals to missed goals** is already done, at read time:
 *   `GoalScorer.score` returns `MISSED` for a `PENDING` answer (A2.1). Writing it here would
 *   duplicate a tested rule and persist a derived value, which `CLAUDE.md` forbids. An unresolved
 *   deferral is self-limiting anyway — `CheckInContent` carries it into exactly one morning check-in.
 *
 * ### Catch-up is the ordinary case
 *
 * The device can be off at 04:00, and no document says what then. Both rules below are expressed
 * over **stored state versus today**, never over "the day that just ended", so a run after a
 * three-day gap resolves all three days at once and a second run on the same day changes nothing.
 * That property is what makes a missed run harmless rather than a permanent hole in the record.
 */
object RolloverPlanner {

    /** How long a measured value stays provisional after the read that produced it (spec O4). */
    private val ProvisionalWindow: Duration = Duration.ofHours(24)

    fun plan(
        today: LocalDate,
        checkIns: List<CheckIn>,
        measuredValues: List<MeasuredValue> = emptyList(),
        now: Instant,
    ): RolloverPlan = RolloverPlan(
        checkInsToMiss = checkIns.filter { it.isNowMissed(today) },
        valuesToFreeze = measuredValues.filter { it.isReadyToFreeze(now) },
    )

    /**
     * A check-in nobody answered before its grace closed.
     *
     * Only `PENDING` becomes `MISSED`. An `ANSWERED` one is finished, and one already `MISSED` is
     * skipped rather than rewritten — which is what lets the job run twice in a day without
     * restamping rows. A late answer arriving afterwards does not undo this: it records `LATE` and
     * the check-in stays missed (A1.2), which is the rule that stops a week filled in on Sunday
     * reporting a flattering number (A1.5).
     */
    private fun CheckIn.isNowMissed(today: LocalDate): Boolean =
        state == CheckInState.PENDING && Grace.isPastGrace(day, today)

    /**
     * A measured value whose provisional window has elapsed.
     *
     * Spec O4 holds a value provisional for 24h after the read that produced it, then freezes it —
     * chosen so a late-syncing step count can still correct itself without permanently mis-scoring
     * the day. So the clock runs from the sync, not from the day being measured: a value re-synced
     * late is still young.
     *
     * **A null `lastSyncedAt` freezes nothing.** Nothing populates that column until Health Connect
     * arrives in M7, so today this branch is the only one taken and the rule finds no work. Guessing
     * an anchor — the day, the row's creation — would freeze a value the app has no evidence about,
     * and O4 exists precisely because that evidence is not in yet.
     */
    private fun MeasuredValue.isReadyToFreeze(now: Instant): Boolean {
        if (state != MeasuredState.PROVISIONAL) return false
        val syncedAt = lastSyncedAt ?: return false
        return !now.isBefore(syncedAt.plus(ProvisionalWindow))
    }
}
