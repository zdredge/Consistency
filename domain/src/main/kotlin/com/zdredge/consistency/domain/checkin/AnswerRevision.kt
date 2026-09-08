package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import java.time.Instant

/**
 * What happens to an answer that already exists when the user changes it.
 *
 * Storage replaces the row wholesale, so without this the second write would restamp `submitted_at`
 * and re-resolve `capture` — losing *when* and *how* the answer was first given. Spec §3.2 requires
 * the opposite: **"History is editable; edits set the edited flag. Never a silent overwrite."** And
 * constraint 4 keeps `capture` and `edited_at` as two separate fields precisely so a backfilled
 * answer that was later corrected stays describable as both.
 *
 * It lives here rather than in the ViewModel because it is a rule that can be *wrong* rather than
 * merely ugly — the sort of wrong that produces a plausible number nobody questions (`CLAUDE.md`).
 *
 * ### What counts as an edit
 *
 * **A change made through a different check-in than the one that first recorded the answer.**
 * Corrections made while giving a check-in are part of that answering, not a later revision — and
 * that includes corrections from its own summary, which exists to invite them. If every Back-and-fix
 * stamped `edited_at`, the flag would fire on ordinary use and stop meaning "this was corrected
 * after the fact", which is the only thing it is for.
 *
 * ### Deferrals are not edits
 *
 * A "not yet" is an answer that was never given, so resolving it the next morning is the *first*
 * recording, not a revision: it takes the new `capture` (which is how the deferral becomes in-window
 * when resolved on time, scoring-cases A2.1/A2.2) and the time it was actually answered, and sets no
 * edit flag. The same applies in reverse — deferring a question that already had an answer must
 * store `PENDING`, or the rollover cannot find it. Both directions are the exception that stops
 * "preserve the capture" from being a one-liner.
 */
object AnswerRevision {

    /**
     * @param existing the answer already stored for this item and day, if any.
     * @param incoming what the user has just given, with `capture` freshly resolved and
     *   `submittedAt` set to now.
     * @param sameCheckIn whether [incoming] arrives through the same check-in that recorded
     *   [existing]. False when the answer is changed later, or outside any check-in at all.
     */
    fun resolve(
        existing: Answer?,
        incoming: Answer,
        sameCheckIn: Boolean,
        now: Instant,
    ): Answer {
        // Nothing to preserve: this is the first recording.
        if (existing == null) return incoming

        val deferralTransition =
            existing.capture == Capture.PENDING || incoming.capture == Capture.PENDING

        return incoming.copy(
            // How and when it was FIRST given, kept across every later change (spec §3.2). A
            // deferral transition is not a later change -- see the note above.
            submittedAt = if (deferralTransition) incoming.submittedAt else existing.submittedAt,
            capture = if (deferralTransition) incoming.capture else existing.capture,
            editedAt = when {
                // Still giving the same check-in. Not a revision.
                sameCheckIn -> existing.editedAt
                // The answer was deferred, never given, so this is its first recording.
                existing.capture == Capture.PENDING -> existing.editedAt
                else -> now
            },
        )
    }
}
