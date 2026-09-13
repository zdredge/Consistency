package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Telling three kinds of blank apart.
 *
 * A day that has not arrived, a day still inside its backfill window, and a day that closed
 * unanswered look identical in storage and must not look identical on a chart. Getting this wrong
 * shows a fortnight of failures to someone who started yesterday.
 */
@DisplayName("Whether a day can still be answered")
class AnswerabilityTest {

    private val today = LocalDate.of(2026, 9, 11)
    private fun day(minus: Long) = today.minusDays(minus)

    @Test
    @DisplayName("3.1 - a night item can be answered for today and yesterday, not the day before")
    fun nightItems() {
        assertTrue(Answerability.isOpen(day(0), Slot.NIGHT, today), "tonight's check-in is due now")
        assertTrue(Answerability.isOpen(day(1), Slot.NIGHT, today), "yesterday is still backfillable")
        assertFalse(Answerability.isOpen(day(2), Slot.NIGHT, today), "the day before has closed")
        assertFalse(Answerability.isFuture(day(0), Slot.NIGHT, today))
    }

    @Test
    @DisplayName("3.1 - tonight cannot be answered for a morning item, because this morning wrote last night")
    fun morningItemsCannotAnswerTonight() {
        // Bedtime for tonight is written by tomorrow morning's check-in. Treating today as answerable
        // would show a permanently unanswered square at the end of every sleep chart.
        assertTrue(Answerability.isFuture(day(0), Slot.MORNING, today))
        assertEquals(day(1), Answerability.latestAnswerDay(today, Slot.MORNING))
        assertEquals(day(0), Answerability.latestAnswerDay(today, Slot.NIGHT))
    }

    @Test
    @DisplayName("a morning item reaches one night further back, because grace runs on the check-in")
    fun morningItemsReachFurtherBack() {
        // The night of T-2 was written by T-1's check-in, which is still inside its window. Asking
        // Grace about the night itself would close a window that is demonstrably still open.
        assertTrue(Answerability.isOpen(day(1), Slot.MORNING, today), "this morning's check-in")
        assertTrue(Answerability.isOpen(day(2), Slot.MORNING, today), "yesterday morning's, still backfillable")
        assertFalse(Answerability.isOpen(day(3), Slot.MORNING, today))
    }

    @Test
    @DisplayName("a weekly question follows its Sunday")
    fun weeklyQuestions() {
        val sunday = LocalDate.of(2026, 9, 13)
        assertTrue(Answerability.isFuture(sunday, Slot.WEEKLY, today), "this week has not been asked yet")
        assertTrue(Answerability.isOpen(LocalDate.of(2026, 9, 6), Slot.WEEKLY, LocalDate.of(2026, 9, 7)))
        assertFalse(Answerability.isOpen(LocalDate.of(2026, 9, 6), Slot.WEEKLY, LocalDate.of(2026, 9, 8)))
    }

    @Test
    @DisplayName("a measured item follows the same days as a night item")
    fun measuredItems() {
        // Steps are never asked, but the rollover reads yesterday, so the same two days are live.
        assertTrue(Answerability.isOpen(day(0), Slot.NONE, today))
        assertTrue(Answerability.isOpen(day(1), Slot.NONE, today))
        assertFalse(Answerability.isOpen(day(2), Slot.NONE, today))
    }

    @Test
    @DisplayName("the check-in day and the answer day invert each other for every slot")
    fun theInverseHolds() {
        for (slot in Slot.entries) {
            val answerDay = AnswerDay.forCheckIn(today, slot)
            assertEquals(today, AnswerDay.checkInDayFor(answerDay, slot), slot.name)
        }
    }
}
