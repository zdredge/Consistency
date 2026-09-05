package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Which day an answer belongs to, given the check-in it was given in.
 *
 * This is the sleep-day convention as one function (spec §3.1). Bedtime at 23:30 falls on day N
 * under the 04:00 rule but waking at 08:30 falls on N+1, so if those answers landed on the days they
 * physically happened, sleep duration would become a cross-day computation and day N's record would
 * be permanently incomplete. All four sleep items therefore attach to **the day the user went to
 * bed**, which means the morning check-in of day N writes answers dated N−1.
 *
 * M3 proved the schema can express this; this is the rule that decides it, and the screen must show
 * the result unmistakably (spec §5.6).
 */
@DisplayName("Answer dating - spec 3.1, the sleep-day convention")
class AnswerDayTest {

    private val wednesday = LocalDate.of(2026, 8, 26)

    @Test
    @DisplayName("a morning check-in writes answers dated the previous day")
    fun theMorningCheckInWritesYesterday() {
        // The architecture worked example: check-in 813 is dated the 26th and its answers the 25th.
        assertEquals(LocalDate.of(2026, 8, 25), AnswerDay.forCheckIn(wednesday, Slot.MORNING))
    }

    @Test
    @DisplayName("a night check-in writes answers dated its own day")
    fun theNightCheckInWritesToday() {
        assertEquals(wednesday, AnswerDay.forCheckIn(wednesday, Slot.NIGHT))
    }

    @Test
    @DisplayName("the weekly slot dates to its own day, so the week resolves from the date")
    fun theWeeklySlotWritesToday() {
        // Weekly items ride along with Sunday night's check-in and are dated that Sunday. The week
        // is derived from the date by DayResolver and never stored (constraint 7).
        val sunday = LocalDate.of(2026, 8, 30)
        assertEquals(sunday, AnswerDay.forCheckIn(sunday, Slot.WEEKLY))
    }

    @Test
    @DisplayName("a month boundary does not confuse the previous-day step")
    fun crossingAMonthBoundaryStillStepsBackOneDay() {
        assertEquals(
            LocalDate.of(2026, 8, 31),
            AnswerDay.forCheckIn(LocalDate.of(2026, 9, 1), Slot.MORNING),
        )
    }

    @Test
    @DisplayName("a measured item has no check-in, so its day is the day it measures")
    fun measuredItemsDateToTheirOwnDay() {
        // Slot.NONE items are never asked (spec 3.3). Nothing should route them through a check-in,
        // but if it does, they must not be silently shifted back a day like the sleep items.
        assertEquals(wednesday, AnswerDay.forCheckIn(wednesday, Slot.NONE))
    }
}
