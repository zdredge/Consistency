package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate

/**
 * Whether a day can still be answered, has not arrived, or has closed unanswered.
 *
 * **A blank square means three different things and the screen has to tell them apart.** A day the
 * user has not reached yet is not a failure; a day still inside its backfill window is not a failure
 * either, because answering it now still counts; only a day that has run out of grace is a day that
 * went unanswered. Drawing all three the same way would show a fortnight of failures to someone who
 * started yesterday.
 *
 * Built entirely from [AnswerDay] and [Grace] and holding no boundary of its own. Grace is measured
 * through the **check-in**, not the answer: a morning item's answer for two days ago was written by
 * *yesterday's* check-in, which is still open, so that day is still answerable. Restating "yesterday"
 * here is exactly the drift `CLAUDE.md` forbids.
 */
object Answerability {

    /**
     * The most recent day this slot could have an answer for.
     *
     * Today for a night or weekly item; **yesterday** for a morning one, because this morning's
     * check-in wrote last night (spec §3.1). Tonight's bedtime cannot be answered until tomorrow.
     */
    fun latestAnswerDay(today: LocalDate, slot: Slot): LocalDate = AnswerDay.forCheckIn(today, slot)

    /** Whether [day] has not arrived yet for this slot, so a blank is expected rather than missing. */
    fun isFuture(day: LocalDate, slot: Slot, today: LocalDate): Boolean =
        day.isAfter(latestAnswerDay(today, slot))

    /**
     * Whether [day] can still be answered — either it is due now or its backfill window is open.
     *
     * A day that is still open is never "not answered": the user has not missed it yet.
     */
    fun isOpen(day: LocalDate, slot: Slot, today: LocalDate): Boolean =
        !isFuture(day, slot, today) && !Grace.isPastGrace(AnswerDay.checkInDayFor(day, slot), today)
}
