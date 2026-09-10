package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Slot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * One alarm to set: a check-in, which attempt it is, and when it fires.
 *
 * [requestCode] identifies the alarm to the platform. It is **derived, never stored**, because
 * cancelling an alarm means rebuilding the identical `PendingIntent` — an alarm whose code cannot be
 * reproduced cannot be cancelled, and would go on firing after the check-in was answered.
 */
data class AlarmSpec(
    val day: LocalDate,
    val slot: Slot,
    val attempt: Int,
    val at: Instant,
) {
    /**
     * A stable, collision-free integer for `(day, slot, attempt)`.
     *
     * Six alarms exist per day — two slots, three attempts each — so the day number is scaled by six
     * and the slot and attempt fill the gap. `toEpochDay` for 2026 is around 20,700, so the largest
     * code is comfortably inside `Int`, and it stays that way for roughly nine million years.
     *
     * **A collision here would be invisible.** Two check-ins sharing a code means setting one
     * silently replaces the other, and the symptom is a notification that simply never arrives —
     * indistinguishable from nothing having been due. Hence the uniqueness test.
     */
    val requestCode: Int
        get() = (day.toEpochDay().toInt() * SLOTS_PER_DAY * ATTEMPTS) +
            (slotIndex(slot) * ATTEMPTS) + attempt

    private companion object {
        const val SLOTS_PER_DAY = 2
        const val ATTEMPTS = 3

        fun slotIndex(slot: Slot): Int = if (slot == Slot.MORNING) 0 else 1
    }
}

/**
 * Which alarms should exist right now.
 *
 * Spec §2: *"fire, repeat twice at ~20 min, then mark missed."* The first half is this function. The
 * second half is **not** — the rollover marks check-ins missed when grace closes, and M6 must not add
 * a second writer for that transition (`CLAUDE.md`; spec §3.2 reconciles the wording: §2 describes
 * the notification sequence stopping, not the window shutting). After the third attempt this simply
 * stops notifying.
 *
 * Nothing here escalates beyond those two repeats. Louder-after-misses is permanently out of scope
 * (spec §2), because muting is the failure mode that ends the product — which is also why
 * confrontation lives at app-open instead.
 *
 * ### Set the whole window, never chain
 *
 * This returns *every* alarm that should exist between now and the end of [through], and the
 * scheduler sets all of them each time it runs. The obvious alternative — each firing arms the next —
 * is one missed firing away from silence for ever, and silence is indistinguishable from "nothing
 * was due". Recomputing the whole window instead makes every run self-healing, the same property
 * that made the rollover's inexact scheduling acceptable in M5.
 */
object AlarmPlanner {

    /** Spec §2: "repeat twice at ~20 min". */
    val RepeatInterval: Duration = Duration.ofMinutes(20)

    /** The first prompt plus its two repeats. */
    const val ATTEMPTS_PER_CHECK_IN = 3

    fun plan(
        checkIns: List<CheckIn>,
        now: Instant,
        through: LocalDate,
    ): List<AlarmSpec> = checkIns
        .filter { it.needsPrompting(through) }
        .flatMap { checkIn ->
            (0 until ATTEMPTS_PER_CHECK_IN).map { attempt ->
                AlarmSpec(
                    day = checkIn.day,
                    slot = checkIn.slot,
                    attempt = attempt,
                    at = checkIn.scheduledAt.plus(RepeatInterval.multipliedBy(attempt.toLong())),
                )
            }
        }
        // An alarm in the past would fire the instant it is set, so a scheduler run at 21:30 would
        // re-post the 21:00 prompt it already delivered.
        .filter { !it.at.isBefore(now) }
        .sortedBy { it.at }

    /**
     * Only a check-in still waiting to be answered, and only inside the window being planned.
     *
     * `ANSWERED` is finished. `MISSED` is past its grace and prompting it would be asking for
     * something that can no longer repair the metric (A1.2) — the record still accepts a late
     * answer, but the app should not go on nagging for one.
     */
    private fun CheckIn.needsPrompting(through: LocalDate): Boolean =
        state == CheckInState.PENDING && !day.isAfter(through)
}
