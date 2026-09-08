package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.Capture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What a second write does to an answer that already exists.
 *
 * The behaviour this replaces was a silent overwrite: storage swaps the row wholesale, so correcting
 * an answer restamped `submitted_at` and re-resolved `capture`, destroying the record of when and
 * how it was first given. Spec §3.2 forbids exactly that, and constraint 4 keeps the two fields
 * apart so a backfilled-then-edited answer stays describable. Recorded as defect 3 of M4.5.
 *
 * These are the tests that stop it coming back, and the interesting ones are the deferral cases —
 * "preserve the capture" is nearly right and wrong in both directions at once.
 */
class AnswerRevisionTest {

    private val firstGiven = Instant.parse("2026-09-07T21:30:00Z")
    private val now = Instant.parse("2026-09-10T09:00:00Z")

    private fun incoming(capture: Capture = Capture.IN_WINDOW, number: Double? = 3.0) =
        answer("meals", number = number, capture = capture).copy(submittedAt = now)

    @Test
    @DisplayName("a first recording is stored exactly as given")
    fun firstWrite() {
        val result = AnswerRevision.resolve(
            existing = null,
            incoming = incoming(),
            sameCheckIn = true,
            now = now,
        )

        assertEquals(now, result.submittedAt)
        assertEquals(Capture.IN_WINDOW, result.capture)
        assertNull(result.editedAt, "a first answer has never been edited")
    }

    @Test
    @DisplayName("correcting within the same check-in is not an edit")
    fun sameCheckIn() {
        val existing = answer("meals", number = 2.0, capture = Capture.BACKFILLED)
            .copy(submittedAt = firstGiven)

        val result = AnswerRevision.resolve(existing, incoming(), sameCheckIn = true, now = now)

        assertEquals(3.0, result.valueNumber, "the new value is what gets stored")
        assertEquals(firstGiven, result.submittedAt, "still the time it was first given")
        assertEquals(Capture.BACKFILLED, result.capture, "still how it was first captured")
        assertNull(result.editedAt, "walking back through a set is not correcting it later")
    }

    @Test
    @DisplayName("3.4 - changing it through a later check-in sets edited_at and leaves capture alone")
    fun laterCheckInIsAnEdit() {
        val existing = answer("meals", number = 2.0, capture = Capture.BACKFILLED)
            .copy(submittedAt = firstGiven)

        val result = AnswerRevision.resolve(existing, incoming(), sameCheckIn = false, now = now)

        assertEquals(firstGiven, result.submittedAt)
        assertEquals(Capture.BACKFILLED, result.capture, "an edit never re-captures")
        assertEquals(now, result.editedAt)
    }

    @Test
    @DisplayName("A2.1 - resolving a deferral takes the new capture and is not an edit")
    fun resolvingADeferral() {
        // "Not yet" last night: a value-less answer whose capture is PENDING. It is answered the
        // next morning, through a different check-in -- which is a first recording, not a revision.
        val deferred = answer("meals", number = null, capture = Capture.PENDING)
            .copy(submittedAt = firstGiven)

        val result = AnswerRevision.resolve(deferred, incoming(), sameCheckIn = false, now = now)

        assertEquals(Capture.IN_WINDOW, result.capture, "a resolved deferral must leave PENDING")
        assertEquals(now, result.submittedAt, "answered now, not when it was deferred")
        assertNull(result.editedAt, "it was never given before, so nothing was corrected")
    }

    @Test
    @DisplayName("A2.1 - deferring an answered question stores PENDING")
    fun deferringAnAnsweredQuestion() {
        val existing = answer("meals", number = 2.0, capture = Capture.IN_WINDOW)
            .copy(submittedAt = firstGiven)

        val result = AnswerRevision.resolve(
            existing = existing,
            incoming = incoming(capture = Capture.PENDING, number = null),
            sameCheckIn = true,
            now = now,
        )

        // Preserving the old capture here would lose the deferral entirely, and the rollover finds
        // unresolved deferrals by looking for PENDING (A2.2).
        assertEquals(Capture.PENDING, result.capture)
    }

    @Test
    @DisplayName("an answer edited twice carries the most recent edit")
    fun editedTwice() {
        val alreadyEdited = answer("meals", number = 2.0, capture = Capture.IN_WINDOW)
            .copy(submittedAt = firstGiven, editedAt = Instant.parse("2026-09-08T08:00:00Z"))

        val result = AnswerRevision.resolve(alreadyEdited, incoming(), sameCheckIn = false, now = now)

        assertEquals(now, result.editedAt)
        assertEquals(firstGiven, result.submittedAt, "the first giving never moves")
    }

    @Test
    @DisplayName("an edit made outside any check-in is still an edit")
    fun editedOutsideACheckIn() {
        val existing = answer("meals", number = 2.0).copy(submittedAt = firstGiven)

        val result = AnswerRevision.resolve(existing, incoming(), sameCheckIn = false, now = now)

        assertEquals(now, result.editedAt)
    }
}
