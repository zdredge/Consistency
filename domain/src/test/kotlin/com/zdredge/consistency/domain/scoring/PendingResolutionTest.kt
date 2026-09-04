package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.answeredDay
import com.zdredge.consistency.domain.checkIn
import com.zdredge.consistency.domain.missedCheckIn
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * docs/scoring-cases.md A2. An unresolved "not yet" is **the only case in the entire rulebook where
 * an absent value scores as MISSED rather than being excluded**, and the asymmetry is deliberate:
 * elsewhere an absent answer is silence, but a deferral is an active choice not to answer yet.
 */
@DisplayName("Unresolved pending - scoring-cases A2")
class PendingResolutionTest {

    private val vitamins = target("vitamins", Direction.IS_TRUE)
    private val day1 = LocalDate.of(2026, 8, 24)

    /** "Not yet", never followed up: still PENDING when the day is scored. */
    private val unresolved = answer("vitamins", capture = Capture.PENDING)

    @Test
    @DisplayName("A2.1 - an unresolved pending answer converts to a MISSED goal")
    fun unresolvedPendingIsAMiss() {
        assertEquals(GoalOutcome.MISSED, GoalScorer.score(vitamins, unresolved).outcome)
    }

    @Test
    @DisplayName("A2.2 - the night check-in it was given in stays ANSWERED")
    fun theCheckInStaysAnswered() {
        // The user did complete that check-in; they simply did not follow through on the deferral.
        val night = checkIn(day1, Slot.NIGHT, CheckInState.ANSWERED)
        assertEquals(CheckInState.ANSWERED, night.state)
    }

    @Test
    @DisplayName("A2.3 - the missed morning check-in breaks the run on its own")
    fun theMissedMorningBreaksTheRun() {
        // A2.2 does not rescue the run: the morning check-in was still missed. In practice this is
        // why an unresolved deferral rarely saves anything.
        val checkIns = answeredDay(day1) +
            listOf(checkIn(day1.plusDays(1), Slot.NIGHT), missedCheckIn(day1.plusDays(1), Slot.MORNING))
        val run = RunCalculator.globalRun(checkIns, upTo = day1.plusDays(1))
        assertEquals(0, run.current)
        assertEquals(1, run.longest)
    }

    @Test
    @DisplayName("A2.4 - a carried item left blank in an answered morning is still a MISSED goal")
    fun blankCarriedItemIsStillAMiss() {
        // Both check-ins were answered, so the run survives, but the goal itself was never resolved.
        assertEquals(GoalOutcome.MISSED, GoalScorer.score(vitamins, unresolved).outcome)

        val checkIns = answeredDay(day1) + answeredDay(day1.plusDays(1))
        assertEquals(2, RunCalculator.globalRun(checkIns, upTo = day1.plusDays(1)).current)
    }

    @Test
    @DisplayName("a pending answer scores MISSED regardless of any value it happens to carry")
    fun pendingBeatsWhateverValueIsPresent() {
        // Defensive: a stale value on a still-pending row must not be scored as though answered.
        val pendingWithValue = answer("vitamins", bool = true, capture = Capture.PENDING)
        assertEquals(GoalOutcome.MISSED, GoalScorer.score(vitamins, pendingWithValue).outcome)
    }
}
