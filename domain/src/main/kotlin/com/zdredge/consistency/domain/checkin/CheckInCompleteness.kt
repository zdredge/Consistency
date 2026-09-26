package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalDate

/**
 * How much of one check-in has something recorded against it.
 *
 * **The one definition of "this question has an answer".** Two callers need it and must agree:
 * marking a check-in answered (response rate, the primary metric, counts that state) and the Home
 * list of answered check-ins still open to changes, which says how many questions were left. If the
 * two drifted, Home could call a check-in complete that the metric counted as unanswered, or the
 * reverse.
 *
 * - **A measured entry is never asked**, so it is neither answered nor unanswered: steps are read,
 *   not given (spec §3.3).
 * - **A deferral counts as answered.** "Not yet" is a response the app deliberately offers, and a
 *   check-in answered that way stays answered (scoring-cases A2.2).
 * - **An entry's answer is looked up on the day it is dated to**, not the check-in's day: a sleep item
 *   in the morning check-in belongs to last night (spec §3.1), and a carried-over deferral to the
 *   night it was deferred from.
 */
data class CheckInCompleteness(
    /** Questions the check-in asks. Read-only entries are not questions. */
    val asked: Int,
    /** Of those, how many have no answer row at all. */
    val unanswered: Int,
) {
    /** Whether the user responded to anything -- what marking the check-in answered requires. */
    val responded: Boolean get() = unanswered < asked

    companion object {
        /**
         * @param hasAnswer whether a row exists for an item on a day. A row with nothing in it is a
         *   real answer ("none of these"), so this asks about the row, not its content.
         */
        fun of(
            entries: List<CheckInEntry>,
            checkInDay: LocalDate,
            slot: Slot,
            hasAnswer: (ItemId, LocalDate) -> Boolean,
        ): CheckInCompleteness {
            val asked = entries.filterNot { it.readOnly }
            return CheckInCompleteness(
                asked = asked.size,
                unanswered = asked.count { entry ->
                    !hasAnswer(entry.item.id, entry.carriedOverFrom ?: AnswerDay.forCheckIn(checkInDay, slot))
                },
            )
        }
    }
}

/**
 * An answered check-in still inside its backfill window, and how many of its questions have no
 * answer.
 *
 * Answering one question marks a check-in answered, which takes it off the outstanding banner -- so
 * before this existed a question skipped inside a finished check-in could not be reached again, even
 * though spec §3.2 keeps it answerable until the end of the next day.
 */
data class ReviewableCheckIn(
    val checkIn: CheckIn,
    val unanswered: Int,
)
