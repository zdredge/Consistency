package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.time.DayResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * How an answer's capture state is decided at the moment it is recorded.
 *
 * M2 tested what the rulebook does *with* a capture state; this decides which one an answer gets,
 * and it is the input response rate -- the primary metric -- is computed from. A wrong rule here
 * produces a plausible wrong percentage rather than a failure, which is the whole reason it lives in
 * a pure function rather than in the screen that calls it.
 *
 * **The window is the check-in's own day**, which ends at 04:00, not at the notification. Spec §2's
 * "repeat twice at ~20 min, then mark missed" describes the notification sequence stopping; §3.2
 * gives the grace as "the end of the next day", and scoring-case 3.2 makes an answer given the next
 * morning BACKFILLED. Those only fit together if the window is the day. Practically: answering at
 * 22:00, an hour after the 21:00 prompt, still counts.
 */
@DisplayName("Capture resolution - spec 3.2, scoring-cases 3.2, 3.3, A1.1")
class CaptureResolverTest {

    private val zone = ZoneId.of("America/New_York")

    /** The night check-in of Tuesday 25 August 2026. */
    private val checkInDay = LocalDate.of(2026, 8, 25)

    @Test
    @DisplayName("an answer given during the check-in's own evening is IN_WINDOW")
    fun answeredTheSameEveningIsInWindow() {
        assertEquals(Capture.IN_WINDOW, captureAt("2026-08-25T21:14"))
    }

    @Test
    @DisplayName("an answer an hour after the prompt is still IN_WINDOW, not a backfill")
    fun answeredLateThatEveningIsStillInWindow() {
        // The case the "window closes after the escalation repeats" reading would have got wrong.
        assertEquals(Capture.IN_WINDOW, captureAt("2026-08-25T22:00"))
    }

    @Test
    @DisplayName("an answer at 03:59 still belongs to the check-in's day and is IN_WINDOW")
    fun answeredJustBeforeTheDayBoundaryIsInWindow() {
        // Constraint 1: the day runs 04:00-03:59, so this is still Tuesday.
        assertEquals(Capture.IN_WINDOW, captureAt("2026-08-26T03:59"))
    }

    @Test
    @DisplayName("3.2 - an answer given the next morning is BACKFILLED")
    fun answeredTheNextMorningIsBackfilled() {
        // 04:00 starts the next day, so this is the first moment the window is shut.
        assertEquals(Capture.BACKFILLED, captureAt("2026-08-26T04:00"))
        assertEquals(Capture.BACKFILLED, captureAt("2026-08-26T08:55"))
    }

    @Test
    @DisplayName("an answer at the very end of the grace day is still BACKFILLED")
    fun answeredAtTheEndOfTheGraceDayIsBackfilled() {
        // Spec 3.2: backfill is allowed until the end of the next day. 03:59 on the 27th is still
        // the 26th under the 04:00 rule, so the grace has not closed.
        assertEquals(Capture.BACKFILLED, captureAt("2026-08-27T03:59"))
    }

    @Test
    @DisplayName("A1.1 - an answer after the grace window closes is LATE")
    fun answeredAfterGraceIsLate() {
        assertEquals(Capture.LATE, captureAt("2026-08-27T04:00"))
        assertEquals(Capture.LATE, captureAt("2026-08-30T12:00"))
    }

    @Test
    @DisplayName("3.3 - a 'not yet' resolved in the next morning's check-in is IN_WINDOW")
    fun resolvingADeferralTheNextMorningIsInWindow() {
        // The one exception to the rule above, and it is why "not yet" is safe to offer: the
        // deferral was honoured, so it costs nothing. Without this the same instant is BACKFILLED.
        assertEquals(
            Capture.BACKFILLED,
            captureAt("2026-08-26T08:55", resolvingDeferral = false),
        )
        assertEquals(
            Capture.IN_WINDOW,
            captureAt("2026-08-26T08:55", resolvingDeferral = true),
        )
    }

    @Test
    @DisplayName("resolving a deferral after the grace day has closed is LATE, not rescued")
    fun aDeferralResolvedAfterGraceIsStillLate() {
        // The exception covers the *next morning's check-in*, not any later moment. Otherwise an
        // item deferred once could be answered a week on and still read as in-window.
        assertEquals(
            Capture.LATE,
            captureAt("2026-08-28T09:00", resolvingDeferral = true),
        )
    }

    @Test
    @DisplayName("a deferral itself is recorded as PENDING until it is resolved")
    fun anUnresolvedDeferralIsPending() {
        assertEquals(
            Capture.PENDING,
            CaptureResolver(resolver("2026-08-25T21:14")).deferred(),
        )
    }

    private fun captureAt(local: String, resolvingDeferral: Boolean = false): Capture =
        CaptureResolver(resolver(local)).forAnswer(checkInDay, resolvingDeferral)

    /** A resolver whose clock is frozen at the given local wall-clock time. */
    private fun resolver(local: String) =
        DayResolver(Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone))
}
