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

    /**
     * The check-in that writes an answer dated [answerDay] — the inverse of [forCheckIn].
     *
     * It lives here so the step and its inverse cannot drift apart. Anything asking *whether a day can
     * still be answered* needs it: grace runs on the check-in's date, so a morning answer for two days
     * ago is still reachable through yesterday's check-in. Asking `Grace` about the answer's own day
     * would close a window that is demonstrably still open.
     */
    fun checkInDayFor(answerDay: LocalDate, slot: Slot): LocalDate = when (slot) {
        Slot.MORNING -> answerDay.plusDays(1)
        Slot.NIGHT, Slot.WEEKLY, Slot.NONE -> answerDay
    }

    /**
     * The check-in that asks an item in [itemSlot] about [answerDay], or null for a measured item,
     * which no check-in asks (spec §3.3).
     *
     * The day is [checkInDayFor]'s; the slot is the check-in's, which is not always the item's. A
     * weekly question rides on Sunday **night's** check-in, because there are two check-ins a day and
     * never a third (spec §1). This is how a day on an item's screen finds the check-in to reopen.
     */
    fun checkInFor(answerDay: LocalDate, itemSlot: Slot): CheckInKey? = when (itemSlot) {
        Slot.MORNING -> CheckInKey(checkInDayFor(answerDay, itemSlot), Slot.MORNING)
        Slot.NIGHT, Slot.WEEKLY -> CheckInKey(checkInDayFor(answerDay, itemSlot), Slot.NIGHT)
        Slot.NONE -> null
    }
}

/** A check-in, identified as the schema identifies one: by the day it was expected and its slot. */
data class CheckInKey(val day: LocalDate, val slot: Slot)
