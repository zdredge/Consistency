package com.zdredge.consistency.domain.checkin

import java.time.LocalDate

/**
 * How long a check-in stays answerable, in one place.
 *
 * Spec §3.2: backfill runs **until the end of the next day**, and every day ends at 04:00. So a
 * check-in for day D can still be answered through day D+1, and from D+2 it is past grace — still
 * *answerable*, recorded `LATE`, but no longer able to repair the metric (A1.2) or the run (A1.3).
 *
 * **This exists because two callers must agree and one of them decides the primary metric.**
 * `outstandingCheckIns` uses it to choose what the banner still offers; the rollover uses it to
 * choose what to mark `MISSED`. If those two ever drifted apart, a check-in could fall into the gap
 * between them — no longer offered to the user and never marked missed — and response rate would be
 * quietly wrong with nothing on screen to show it. Restating "yesterday" in two places is exactly how
 * that happens, so it is stated once.
 *
 * Same posture as `DayResolver` owning the 04:00 boundary: the rule that decides a number lives in
 * one function, not in whichever caller needed it first.
 */
object Grace {

    /**
     * The oldest day still inside its backfill window on [today].
     *
     * Today and yesterday are answerable; anything before that is not.
     */
    fun oldestAnswerableDay(today: LocalDate): LocalDate = today.minusDays(1)

    /** Whether a check-in dated [day] has run out of grace by [today]. */
    fun isPastGrace(day: LocalDate, today: LocalDate): Boolean =
        day.isBefore(oldestAnswerableDay(today))
}
