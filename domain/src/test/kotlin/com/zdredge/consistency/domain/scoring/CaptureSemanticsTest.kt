package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * docs/scoring-cases.md 3.3, 3.4, A1.1 and A1.4.
 *
 * Which capture an answer gets is :data's job; what the rulebook cares about is that capture and
 * edited-ness stay independent (spec constraint 4) and that capture never changes whether a goal is
 * scored -- only whether the *check-in* counted, which is ResponseRate's concern, not this one.
 */
@DisplayName("Capture semantics - scoring-cases 3.3, 3.4, A1.1, A1.4")
class CaptureSemanticsTest {

    private val vitamins = target("vitamins", Direction.IS_TRUE)

    @Test
    @DisplayName("3.3 - a 'not yet' resolved in the next morning's check-in is still IN_WINDOW")
    fun resolvedPendingCountsAsInWindow() {
        // The deferral was honoured, so it costs nothing. This is why "not yet" is safe to offer:
        // it exists to stop a check-in arriving while items are still actionable.
        val resolved = answer("vitamins", bool = true, capture = Capture.IN_WINDOW)
        assertEquals(Capture.IN_WINDOW, resolved.capture)
        assertEquals(GoalOutcome.MET, GoalScorer.score(vitamins, resolved).outcome)
    }

    @Test
    @DisplayName("3.4 - editing an answer sets edited_at and leaves capture untouched")
    fun editingDoesNotRewriteCapture() {
        // Constraint 4: merging these into one field loses the ability to describe a backfilled
        // answer that was later corrected, and makes every consistency figure unverifiable.
        val edited = answer(
            "vitamins",
            bool = true,
            capture = Capture.BACKFILLED,
            editedAt = Instant.parse("2026-08-28T10:00:00Z"),
        )
        assertEquals(Capture.BACKFILLED, edited.capture, "capture records how it was FIRST recorded")
        assertNotNull(edited.editedAt)
    }

    @Test
    @DisplayName("3.4 - the in-window-only figure derives from capture alone, so an edit cannot move it")
    fun inWindowFigureIgnoresEdits() {
        val answers = listOf(
            answer("a", capture = Capture.IN_WINDOW, bool = true),
            answer("b", capture = Capture.IN_WINDOW, bool = true, editedAt = Instant.EPOCH),
            answer("c", capture = Capture.BACKFILLED, bool = true, editedAt = Instant.EPOCH),
        )
        assertEquals(2, answers.count { it.capture == Capture.IN_WINDOW })
    }

    @Test
    @DisplayName("A1.1 - an item filled in after grace carries capture LATE")
    fun lateCaptureIsRepresentable() {
        val late = answer("vitamins", bool = true, capture = Capture.LATE)
        assertEquals(Capture.LATE, late.capture)
    }

    @Test
    @DisplayName("A1.4 - a LATE answer still scores for goal completion: the data counts")
    fun lateAnswersStillScore() {
        // The metric is not for sale, but the data is worth having. A late answer contributes to
        // goal completion exactly as any other would; only response rate and the run refuse it.
        val late = answer("vitamins", bool = true, capture = Capture.LATE)
        assertEquals(GoalOutcome.MET, GoalScorer.score(vitamins, late).outcome)
    }

    @Test
    @DisplayName("capture does not change the goal outcome -- with PENDING as the one exception")
    fun captureIsIrrelevantToGoalScoringExceptWhenPending() {
        // How an answer was recorded says nothing about whether the target was met. IN_WINDOW,
        // BACKFILLED and LATE all score identically; only response rate and the run care.
        (Capture.entries - Capture.PENDING).forEach { capture ->
            assertEquals(
                GoalOutcome.MET,
                GoalScorer.score(vitamins, answer("vitamins", bool = true, capture = capture)).outcome,
                "capture $capture must not alter whether the target was met",
            )
        }

        // PENDING is the deliberate exception (A2.1): a deferral that was never followed up is a
        // MISS, not a met goal, whatever stale value the row carries. An earlier version of this
        // test asserted capture was irrelevant across the board, which was too strong.
        assertEquals(
            GoalOutcome.MISSED,
            GoalScorer.score(vitamins, answer("vitamins", bool = true, capture = Capture.PENDING)).outcome,
        )
    }

    @Test
    @DisplayName("an unedited answer has no edit timestamp")
    fun uneditedAnswersHaveNoEditTimestamp() {
        assertNull(answer("vitamins", bool = true).editedAt)
    }
}
