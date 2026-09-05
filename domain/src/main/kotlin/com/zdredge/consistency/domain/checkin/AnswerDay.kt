package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate

/**
 * Which day an answer belongs to, given the check-in it arrives through.
 *
 * **This is the sleep-day convention** (spec §3.1), and it is the reason `answers.day_date` is not
 * derivable from the check-in's date. Bedtime at 23:30 falls on day N under the 04:00 rule but
 * waking at 08:30 falls on N+1; if each answer landed on the day it physically happened, sleep
 * duration would become a cross-day computation and day N's record would be permanently incomplete.
 * So all four sleep items attach to **the day the user went to bed**, and the morning check-in of
 * day N writes answers dated N−1.
 *
 * The consequence the screen must carry: the morning check-in is writing to *yesterday*, and it has
 * to say so unmistakably (spec §5.6). The same label serves the "not yet" carry-over.
 *
 * Note this deliberately does no clock arithmetic. Which day a *timestamp* belongs to is
 * `DayResolver`'s question and the 04:00 boundary applies there; this is a step between two already
 * resolved days, so it needs neither the clock nor the zone.
 */
object AnswerDay {

    /**
     * The day an answer belongs to when given in a [slot] check-in held on [checkInDay].
     *
     * Only [Slot.MORNING] steps back. Night and weekly answers describe the day they are given on,
     * and a weekly answer's *week* is derived from that date rather than stored (constraint 7).
     */
    fun forCheckIn(checkInDay: LocalDate, slot: Slot): LocalDate = when (slot) {
        Slot.MORNING -> checkInDay.minusDays(1)
        Slot.NIGHT, Slot.WEEKLY, Slot.NONE -> checkInDay
    }
}
