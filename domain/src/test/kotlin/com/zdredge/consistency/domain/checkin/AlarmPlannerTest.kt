package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Which prompts should exist, and — the half that is easy to get wrong — which should not.
 *
 * The failure mode this guards against is silence. A wrong rule here does not crash or produce a
 * visibly wrong number; the notification simply never arrives, which looks exactly like nothing
 * having been due. That is why the request-code uniqueness case below is a test rather than a
 * comment.
 */
class AlarmPlannerTest {

    private val today = LocalDate.of(2026, 9, 10)
    private val tomorrow = today.plusDays(1)

    /** 21:00 and 08:00 local on the day, the seed library's two defaults. */
    private val nightAt: Instant = Instant.parse("2026-09-11T01:00:00Z")
    private val morningAt: Instant = Instant.parse("2026-09-10T12:00:00Z")

    /** Before either slot is due, so nothing is filtered out for being in the past. */
    private val beforeBoth: Instant = Instant.parse("2026-09-10T06:00:00Z")

    private fun checkIn(
        day: LocalDate = today,
        slot: Slot = Slot.NIGHT,
        state: CheckInState = CheckInState.PENDING,
        at: Instant = nightAt,
    ) = CheckIn(day = day, slot = slot, state = state, scheduledAt = at)

    private fun plan(vararg checkIns: CheckIn, now: Instant = beforeBoth) =
        AlarmPlanner.plan(checkIns.toList(), now = now, through = tomorrow)

    // ---- The escalation -----------------------------------------------------------------------

    @Test
    @DisplayName("spec 2 - a pending check-in gets the prompt plus two repeats, 20 minutes apart")
    fun threeAttempts() {
        val alarms = plan(checkIn())

        assertEquals(3, alarms.size)
        assertEquals(listOf(0, 1, 2), alarms.map { it.attempt })
        assertEquals(
            listOf(nightAt, nightAt.plusSeconds(1200), nightAt.plusSeconds(2400)),
            alarms.map { it.at },
        )
    }

    @Test
    @DisplayName("spec 2 - nothing escalates beyond the second repeat")
    fun noFourthAttempt() {
        // Louder-after-misses is permanently out of scope: muting is the failure that ends the
        // product, so confrontation lives at app-open instead. The sequence stops, and the rollover
        // -- not this -- decides the check-in is missed.
        assertTrue(plan(checkIn()).none { it.attempt >= 3 })
    }

    @Test
    @DisplayName("both daily slots are prompted")
    fun bothSlots() {
        val morning = checkIn(slot = Slot.MORNING, at = morningAt)

        val alarms = plan(morning, checkIn())

        assertEquals(6, alarms.size)
        assertEquals(listOf(Slot.MORNING, Slot.NIGHT), alarms.map { it.slot }.distinct())
    }

    @Test
    @DisplayName("alarms come back in the order they will fire")
    fun sortedByTime() {
        // Night passed in first, morning second, so an unsorted result would put 21:00 before 08:00.
        val alarms = plan(checkIn(), checkIn(slot = Slot.MORNING, at = morningAt))

        assertEquals(6, alarms.size)
        assertEquals(Slot.MORNING, alarms.first().slot)
        assertEquals(alarms.map { it.at }.sorted(), alarms.map { it.at })
    }

    // ---- What must not be prompted ------------------------------------------------------------

    @Test
    @DisplayName("an answered check-in is never prompted")
    fun answeredIsNotPrompted() {
        assertTrue(plan(checkIn(state = CheckInState.ANSWERED)).isEmpty())
    }

    @Test
    @DisplayName("A1.2 - a missed check-in is not prompted; its grace has closed")
    fun missedIsNotPrompted() {
        // The record still accepts a late answer, but it can no longer repair response rate or the
        // run, so going on nagging for one would be asking for something that no longer counts.
        assertTrue(plan(checkIn(state = CheckInState.MISSED)).isEmpty())
    }

    @Test
    @DisplayName("an alarm whose time has passed is not set")
    fun pastAlarmsAreDropped() {
        // Setting one in the past fires it immediately, so a scheduler run at 21:30 -- which happens
        // every time the app is opened -- would re-post the 21:00 prompt already delivered.
        val alarms = plan(checkIn(), now = nightAt.plusSeconds(1500))

        assertEquals(listOf(2), alarms.map { it.attempt })
    }

    @Test
    @DisplayName("an alarm exactly now still counts as due")
    fun theBoundaryIsInclusive() {
        assertEquals(3, plan(checkIn(), now = nightAt).size)
    }

    @Test
    @DisplayName("a check-in beyond the planning window is left for a later run")
    fun beyondTheWindow() {
        val farOff = checkIn(day = today.plusDays(5), at = Instant.parse("2026-09-16T01:00:00Z"))

        assertTrue(plan(farOff).isEmpty())
    }

    @Test
    @DisplayName("nothing to prompt produces no alarms")
    fun nothingToPrompt() {
        assertTrue(plan().isEmpty())
    }

    // ---- Request codes ------------------------------------------------------------------------

    @Test
    @DisplayName("every alarm across a year of days and slots has its own request code")
    fun requestCodesAreUnique() {
        // A collision is invisible: setting one alarm silently replaces the other, and the symptom
        // is a prompt that never arrives -- which looks exactly like nothing having been due.
        val codes = (0L until 365L).flatMap { offset ->
            val day = today.plusDays(offset)
            listOf(Slot.MORNING, Slot.NIGHT).flatMap { slot ->
                (0 until AlarmPlanner.ATTEMPTS_PER_CHECK_IN).map { attempt ->
                    AlarmSpec(day, slot, attempt, nightAt).requestCode
                }
            }
        }

        assertEquals(365 * 2 * 3, codes.size)
        assertEquals(codes.size, codes.toSet().size, "request codes must not collide")
    }

    @Test
    @DisplayName("a request code depends only on day, slot and attempt")
    fun requestCodesAreStable() {
        // Cancelling an alarm means rebuilding the identical PendingIntent. If the code depended on
        // anything else -- the fire time, say -- an alarm could not be cancelled after the check-in
        // was answered, and would go on firing.
        val one = AlarmSpec(today, Slot.NIGHT, 1, nightAt)
        val other = AlarmSpec(today, Slot.NIGHT, 1, nightAt.plusSeconds(9999))

        assertEquals(one.requestCode, other.requestCode)
    }
}
