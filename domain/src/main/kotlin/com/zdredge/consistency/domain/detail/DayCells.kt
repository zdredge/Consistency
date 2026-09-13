package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.checkin.Answerability
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.GoalResult
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.scoring.GoalScorer
import com.zdredge.consistency.domain.scoring.ItemLifecycle
import com.zdredge.consistency.domain.scoring.MeasuredScorer
import java.time.LocalDate
import java.time.LocalTime

/**
 * What one day of one item amounts to.
 *
 * **A blank square means four different things and the screen has to tell them apart** — the item did
 * not exist, the day has not arrived, the day can still be answered, or the day went unanswered. Only
 * the last is a failure, and drawing them alike would show a month of failures to someone who started
 * last week. Most of the current chart is [NOT_ACTIVE]: real data began on 2026-09-10 and the chart is
 * five weeks long.
 */
enum class DayState {
    /** Before the item was created, after it was retired, or no version in force. Not drawn at all. */
    NOT_ACTIVE,

    /** Later than this item can have an answer for. Tonight's bedtime is answered tomorrow (§3.1). */
    FUTURE,

    /** No answer yet, but the backfill window is open. Not a miss — the user has not missed it yet. */
    OPEN,

    /** The window closed with nothing recorded. Silence: excluded from scoring, never a miss (1.13). */
    NOT_ANSWERED,

    /** Answered "not yet", and still resolvable. Drawn with a dashed outline (§5.4). */
    DEFERRED,

    /** The user said the period did not allow it. Costs nothing, and stays visible (constraint 17). */
    NO_OPPORTUNITY,

    /** Answered, with nothing to judge it against — an observation, or a goal with no daily target. */
    RECORDED,

    MET,
    MISSED,

    /** Measured only: two sources reported this day, so it is not counted (architecture §5). */
    CONFLICTED,
}

/**
 * A day's answer, read through the version that was in force — not by looking for whichever field
 * happens to be populated.
 *
 * An answer carries exactly one value per its item's answer type; reading it any other way would let
 * a stale column left by an earlier version of the question be drawn as though it were the answer.
 */
sealed interface DayValue {
    data class Amount(val value: Double) : DayValue
    data class YesNo(val value: Boolean) : DayValue
    data class TimeOfDay(val value: LocalTime) : DayValue
    data class Rating(val value: Int) : DayValue
    data class Choices(val options: Set<OptionId>) : DayValue
}

/**
 * How the answer was recorded, as distinct from what it says.
 *
 * Spec §5.4 puts backfilled and deferred days on the chart and leaves late answers and edits to the
 * table; all of them are carried here so the table needs no second pass over the data.
 *
 * There is deliberately no mark for a *resolved* deferral. Resolving one replaces the capture through
 * `AnswerRevision`, so nothing is left to mark — only an unresolved deferral can be shown as one.
 */
data class DayMarks(
    val backfilled: Boolean = false,
    val late: Boolean = false,
    val edited: Boolean = false,
    val deferred: Boolean = false,
    val hasNote: Boolean = false,
    val provisional: Boolean = false,
)

/**
 * One day, judged once.
 *
 * **The same cells feed the chart, the figures and the run**, which is what stops the calendar and the
 * hit rate beside it from disagreeing about the same fortnight.
 *
 * [value] is absent whenever there is nothing honest to draw: a deferred row may still carry a stale
 * number, and a conflicted step day carries the two sources' total, which is not a step count.
 * [result] is absent when the day is not a goal instance at all — outside the item's life, in the
 * future, or with no target in force.
 */
data class DayCell(
    val day: LocalDate,
    val state: DayState,
    val value: DayValue? = null,
    val marks: DayMarks = DayMarks(),
    val result: GoalResult? = null,
    val note: String? = null,
)

/**
 * Turns an item's history into one judgement per day.
 *
 * The order of the rules is the rulebook, not an implementation detail, and it is the order
 * `GoalScorer` applies for the same reasons — silence before direction, no-opportunity before
 * direction, a deferral judged on whether it can still be resolved. What this adds is the states a
 * scorer has no opinion about, because scoring has no notion of a day that has not arrived.
 */
object DayCells {

    fun of(history: ItemHistory, days: List<LocalDate>, today: LocalDate): List<DayCell> =
        days.map { cell(history, it, today) }

    private fun cell(history: ItemHistory, day: LocalDate, today: LocalDate): DayCell {
        val version = history.versionOn(day)
        // 1. Not a day this item existed on. An item that did not exist cannot have been skipped.
        if (version == null || !ItemLifecycle.isActiveOn(history.item, day)) {
            return DayCell(day, DayState.NOT_ACTIVE)
        }

        val slot = version.slot
        // 2. Not yet. A morning item's latest answerable day is yesterday, so today is future for it.
        if (Answerability.isFuture(day, slot, today)) return DayCell(day, DayState.FUTURE)

        // A weekly question is judged against its weekly target on the Sunday it is answered; every
        // other item against the daily one. One line rather than a branch, because it is one idea:
        // the target for the period the answer covers.
        val period = if (slot == Slot.WEEKLY) Period.WEEK else Period.DAY
        val target = history.targetResolver.resolve(history.item.id, period, day)

        if (history.item.kind == ItemKind.MEASURED) {
            return measuredCell(history.measuredOn(day), day, slot, today, target)
        }

        val answer = history.answerOn(day)
        val open = Answerability.isOpen(day, slot, today)

        // 3. Nothing recorded. Still answerable is not the same as unanswered, and the difference is
        //    the whole of tonight.
        if (answer == null) {
            return if (open) {
                DayCell(
                    day, DayState.OPEN,
                    result = target?.let { GoalResult.excluded(ExclusionReason.PERIOD_OPEN) },
                )
            } else {
                DayCell(
                    day, DayState.NOT_ANSWERED,
                    result = target?.let { GoalResult.excluded(ExclusionReason.NO_ANSWER) },
                )
            }
        }

        val marks = marksOf(answer)
        val note = answer.note

        // 4. A deferral. Excluded while the morning is still ahead (A2.1), missed once it is not --
        //    and only missed where there is a goal to have missed. A deferral on an item targeted by
        //    the week leaves the day simply unanswered, and the week counts it as an unobserved day.
        if (answer.capture == Capture.PENDING) {
            if (open) {
                return DayCell(
                    day, DayState.DEFERRED, marks = marks, note = note,
                    result = target?.let { GoalResult.excluded(ExclusionReason.PERIOD_OPEN) },
                )
            }
            return DayCell(
                day,
                if (target != null) DayState.MISSED else DayState.NOT_ANSWERED,
                marks = marks,
                note = note,
                result = target?.let { GoalScorer.score(it, answer, history.noOpportunityOptions) },
            )
        }

        val value = valueOf(answer, version)

        // 5. No opportunity, before any direction is considered (10.7). The target it happens to
        //    carry is irrelevant, and so is whether it has one at all.
        if (answer.selections.any { it in history.noOpportunityOptions }) {
            return DayCell(
                day, DayState.NO_OPPORTUNITY, value = value, marks = marks, note = note,
                result = target?.let { GoalResult.excluded(ExclusionReason.NO_OPPORTUNITY) },
            )
        }

        // 6. Recorded, with nothing to judge it against. "Did you stretch?" answered no on a Tuesday
        //    is a recorded no, not a missed goal: stretching is targeted by the week.
        if (target == null || version.classification == Classification.OBSERVATION) {
            return DayCell(day, DayState.RECORDED, value = value, marks = marks, note = note)
        }

        // 7. Scored.
        val result = GoalScorer.score(target, answer, history.noOpportunityOptions)
        return DayCell(day, stateOf(result), value = value, marks = marks, note = note, result = result)
    }

    /**
     * A measured day.
     *
     * It has no check-in, so "open" here means the value could still arrive: the rollover re-reads
     * recent days, and a phone that spent the night off syncs later. Borrowing the backfill window
     * keeps that to one rule rather than inventing a second boundary for steps — today and yesterday
     * are still filling in, while an older day with nothing is a day no record was read, which is not
     * zero steps and must never be scored as a miss.
     */
    private fun measuredCell(
        value: MeasuredValue?,
        day: LocalDate,
        slot: Slot,
        today: LocalDate,
        target: Target?,
    ): DayCell {
        if (value == null) {
            return if (Answerability.isOpen(day, slot, today)) {
                DayCell(
                    day, DayState.OPEN,
                    result = target?.let { GoalResult.excluded(ExclusionReason.PERIOD_OPEN) },
                )
            } else {
                DayCell(day, DayState.NOT_ANSWERED, result = target?.let { MeasuredScorer.score(it, null) })
            }
        }

        val result = target?.let { MeasuredScorer.score(it, value) }

        // The row still holds the origins' total so a flagged day can be investigated; nothing may
        // read that as a step count, so it is not carried up as a value.
        if (value.state == MeasuredState.CONFLICTED) {
            return DayCell(day, DayState.CONFLICTED, result = result)
        }

        return DayCell(
            day = day,
            state = if (result == null) DayState.RECORDED else stateOf(result),
            value = DayValue.Amount(value.value),
            marks = DayMarks(provisional = value.state == MeasuredState.PROVISIONAL),
            result = result,
        )
    }

    /**
     * An excluded outcome lands on [DayState.RECORDED] rather than a state of its own: by the time
     * this is reached the day has an answer, and the only exclusion left is one the direction could
     * not compare. Silence, no-opportunity and open days never reach here.
     */
    private fun stateOf(result: GoalResult): DayState = when (result.outcome) {
        GoalOutcome.MET -> DayState.MET
        GoalOutcome.MISSED -> DayState.MISSED
        GoalOutcome.EXCLUDED -> DayState.RECORDED
    }

    private fun marksOf(answer: Answer) = DayMarks(
        backfilled = answer.capture == Capture.BACKFILLED,
        late = answer.capture == Capture.LATE,
        edited = answer.editedAt != null,
        deferred = answer.capture == Capture.PENDING,
        hasNote = !answer.note.isNullOrBlank(),
    )

    private fun valueOf(answer: Answer, version: ItemVersion): DayValue? = when (version.answerType) {
        AnswerType.BOOL -> answer.valueBool?.let(DayValue::YesNo)
        AnswerType.NUMBER -> answer.valueNumber?.let(DayValue::Amount)
        AnswerType.TIME -> answer.valueTime?.let(DayValue::TimeOfDay)
        AnswerType.SCALE -> answer.valueScale?.let(DayValue::Rating)
        // An empty set is a real answer of "none of these" and is drawn as one, which is why this
        // branch has no null case: the selections are the answer, however few of them there are.
        AnswerType.SINGLE_SELECT, AnswerType.MULTI_SELECT -> DayValue.Choices(answer.selections)
    }
}
