package com.zdredge.consistency.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.domain.checkin.AnswerDay
import com.zdredge.consistency.domain.checkin.CaptureResolver
import com.zdredge.consistency.domain.checkin.CheckInEntry
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the user has typed or tapped for one question, not yet stored.
 *
 * The fields mirror `Answer` deliberately. Exactly one is populated, per the item's answer type, and
 * the mapping to a stored answer is then a copy rather than a translation — one fewer place for a
 * value to land in the wrong column.
 */
data class AnswerDraft(
    val valueBool: Boolean? = null,
    val valueNumber: Double? = null,
    val valueTime: LocalTime? = null,
    val valueScale: Int? = null,
    val selections: Set<OptionId> = emptySet(),
    val note: String = "",
    /**
     * The user tapped "not yet". Stored as a value-less answer with `capture = PENDING`, which is
     * how the rollover finds it to convert into a missed goal if it is never resolved
     * (scoring-cases A2.1). Mutually exclusive with a value: answering clears it, deferring clears
     * the value, because "3 meals, but not yet" is not a thing the record can mean.
     */
    val deferred: Boolean = false,
    /**
     * The user answered a multi-select with **nothing selected** — "I did none of these before bed".
     *
     * This is a different thing from silence and scores differently: an answered-but-empty
     * multi-select *meets* a must-not-include goal, where silence is excluded (spec constraint 11,
     * scoring-cases 1.13). Storage has always distinguished them — an empty answer row versus no row
     * — but until now the screen had no way to say it, so that answer was unreachable. Known gap
     * carried out of M4, closed here.
     */
    val noneSelected: Boolean = false,
) {
    /**
     * Whether the user actually answered.
     *
     * A blank draft is **silence**, and silence is never stored: an empty answer must never satisfy
     * a must-not-include goal (spec constraint 11, scoring-cases 1.13). [noneSelected] is the
     * deliberate exception — an answer whose content is "nothing", which is not the same as no answer.
     */
    val isAnswered: Boolean
        get() = valueBool != null || valueNumber != null || valueTime != null ||
            valueScale != null || selections.isNotEmpty() || noneSelected

    /** Whether there is anything worth writing at all. */
    val hasContent: Boolean get() = isAnswered || deferred
}

/** One question as the screen needs it: what to ask, how to ask it, and what has been entered. */
data class QuestionUi(
    val entry: CheckInEntry,
    val options: List<SelectOption> = emptyList(),
    val draft: AnswerDraft = AnswerDraft(),
    /**
     * Changed since it was last written. Only dirty questions are committed when the user leaves
     * them, so walking back and forth through a set does not rewrite unchanged answers — which
     * would keep bumping `submitted_at` and make the record say the answer was given later than it
     * was.
     */
    val dirty: Boolean = false,
    /**
     * A measured day more than one source reported, so it is deliberately not counted.
     *
     * Distinct from "no value yet", because the two need different words on screen: one is waiting
     * for data, the other has too much of it and cannot tell which is true. Showing a number here
     * would be the double-count the origin guard exists to prevent (architecture §5, §8).
     */
    val measuredConflicted: Boolean = false,
) {
    val prompt: String get() = entry.version.prompt
    val answerType: AnswerType get() = entry.version.answerType
    val unitLabel: String? get() = entry.version.unitLabel
    val readOnly: Boolean get() = entry.readOnly
}

data class CheckInUiState(
    val loading: Boolean = true,
    val checkInDay: LocalDate? = null,
    val slot: Slot? = null,
    /**
     * The day the answers are written to, which is **not** always the check-in's own day: a morning
     * check-in writes yesterday (spec §3.1). The screen must state this unmistakably (spec §5.6),
     * which is why it is state rather than something the UI recomputes.
     */
    val answersDay: LocalDate? = null,
    val questions: List<QuestionUi> = emptyList(),
    /** Which question is on screen. One question at a time (M4.5). */
    val index: Int = 0,
    /**
     * The summary is showing: the questions are done with and the user is looking at what they gave.
     *
     * It is a **page of this screen, not a screen of its own**. It needs the same state holder, the
     * same header and the same progress bar, and routing it through `MainActivity` would have put it
     * behind the exit signal — which was defect 1, now fixed by making that signal an event rather
     * than a field. The reasoning held up: the summary still has no business being a screen.
     */
    val onSummary: Boolean = false,
    /**
     * The question on screen was opened *from* the summary, so leaving it returns there rather than
     * advancing. Entry decides exit: the same question is reached two ways and has to leave the way
     * it came, or correcting one answer would dump the user back into the middle of a set they had
     * already finished.
     */
    val fromSummary: Boolean = false,
) {
    val current: QuestionUi? get() = questions.getOrNull(index)
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index >= questions.lastIndex

    /** For the progress bar: which questions have something recorded against them. */
    val answeredIndices: Set<Int>
        get() = questions.indices.filter { questions[it].draft.hasContent }.toSet()
}

/**
 * The check-in screen's state holder.
 *
 * It is deliberately thin. Every decision that could be *wrong* rather than merely ugly — which day
 * an answer belongs to, which capture state it earns, which questions are asked at all — lives in
 * `:domain` and is tested there without a device. What is left here is assembly and position, which
 * is why the screen above it can be verified by hand.
 *
 * **Answers are written as the user leaves each question**, not all at once at the end. The app is
 * barely running most of the time (architecture §1.1), so an answer that only exists in memory is an
 * answer one process death away from never having happened. The cost is that a half-finished
 * check-in leaves real rows behind — which is correct, and why finishing is what marks the check-in
 * answered rather than answering anything does.
 */
class CheckInViewModel(
    private val repository: ConsistencyRepository,
    private val dayResolver: DayResolver,
) : ViewModel() {

    private val capture = CaptureResolver(dayResolver)

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

    /**
     * "This check-in is done with" — delivered **once**, to whoever is navigating.
     *
     * It was a `Boolean` on the state, and that was defect 1. A field outlives the screen that set
     * it: `MainActivity` navigates on seeing it true, `load()` cleared it in a coroutine, and
     * re-entering the screen raced the two — so every *first* reopen after a close bounced straight
     * back to the home screen and only the second worked. An event cannot be observed twice, so the
     * race has nowhere to live rather than being timed more carefully.
     */
    private val _exit = Channel<Unit>(Channel.BUFFERED)
    val exit: Flow<Unit> = _exit.receiveAsFlow()

    /**
     * Loads a check-in, or does nothing if it is already loaded.
     *
     * The guard matters: the screen calls this from a `LaunchedEffect`, which re-runs when the
     * composition restarts. Reloading would reset the position to the first question, so a rotation
     * mid-check-in would silently send the user back to the start.
     */
    fun load(day: LocalDate, slot: Slot) {
        val current = _state.value
        // Rotation re-runs the screen's LaunchedEffect, and reloading there would send the user back
        // to the first question mid-check-in. Leaving resets the state to `loading`, so re-entering
        // fails this guard and rebuilds — no flag involved.
        if (!current.loading && current.checkInDay == day && current.slot == slot) {
            return
        }

        viewModelScope.launch {
            val entries = repository.checkInQuestions(day, slot)

            // Read steps before building the drafts, not after. Spec §3.3 wants today's figure
            // visible "in the moment", and at 21:00 the number read at 04:15 is most of a day stale.
            // Skipped entirely when nothing measured is on this check-in, so a morning check-in
            // never touches Health Connect.
            if (entries.any { it.readOnly }) repository.syncSteps(day)

            val questions = entries.map { entry ->
                QuestionUi(
                    entry = entry,
                    options = if (entry.version.answerType.isSelect) {
                        repository.activeOptions(entry.item.id)
                    } else {
                        emptyList()
                    },
                    draft = existingDraft(entry, day, slot),
                    measuredConflicted = entry.readOnly && isConflicted(entry, day, slot),
                )
            }

            _state.value = CheckInUiState(
                loading = false,
                checkInDay = day,
                slot = slot,
                answersDay = AnswerDay.forCheckIn(day, slot),
                questions = questions,
            )
        }
    }

    /**
     * Pre-fills from an answer already given, so returning to a check-in — to correct something, or
     * to finish it — shows what is there rather than a blank form.
     */
    private suspend fun existingDraft(
        entry: CheckInEntry,
        day: LocalDate,
        slot: Slot,
    ): AnswerDraft {
        val answersDay = entry.carriedOverFrom ?: AnswerDay.forCheckIn(day, slot)

        // A measured item has no answer row and never will -- it is read, not given. Looking it up
        // in `answers` is why the screen showed "Not available yet" even with a value stored.
        if (entry.readOnly) {
            val measured = repository.measuredValue(entry.item.id, answersDay)
            return AnswerDraft(
                // A conflicted day carries a total, but it is diagnostic, not a step count. Handing
                // it to the screen would display the sum the guard refused to make.
                valueNumber = measured?.value
                    ?.takeIf { measured.state != MeasuredState.CONFLICTED },
            )
        }

        val existing = repository.answer(entry.item.id, answersDay) ?: return AnswerDraft()
        return AnswerDraft(
            valueBool = existing.valueBool,
            valueNumber = existing.valueNumber,
            valueTime = existing.valueTime,
            valueScale = existing.valueScale,
            selections = existing.selections,
            note = existing.note.orEmpty(),
            // Reopening a check-in that was deferred must show it as deferred, not as blank.
            // Blank would read as "never answered" and quietly drop the deferral on re-submit.
            deferred = existing.capture == Capture.PENDING,
            // A stored multi-select row with no selections is the "none of these" answer. Reloading
            // it as a blank draft would turn a real answer back into silence on the next commit.
            noneSelected = entry.version.answerType == AnswerType.MULTI_SELECT &&
                existing.selections.isEmpty() &&
                existing.capture != Capture.PENDING,
        )
    }

    private suspend fun isConflicted(entry: CheckInEntry, day: LocalDate, slot: Slot): Boolean {
        val answersDay = entry.carriedOverFrom ?: AnswerDay.forCheckIn(day, slot)
        return repository.measuredValue(entry.item.id, answersDay)?.state ==
            MeasuredState.CONFLICTED
    }

    // ---- Navigation --------------------------------------------------------------------------

    /** Advances, committing whatever is on screen. Works with no answer given — skipping is real. */
    fun next() = moveTo(_state.value.index + 1)

    fun back() = moveTo(_state.value.index - 1)

    /**
     * Ends the set: commits the last question, **marks the check-in answered**, and shows the summary.
     *
     * Marking here is the single most consequential action in the app — response rate, the primary
     * metric, counts check-ins in this state. Answering questions does not do it; reaching the end
     * does.
     *
     * **The summary is a review of a completed check-in, not a gate before one.** Confirming does not
     * mark anything, so a user who reads the summary and closes instead is already counted. That is a
     * deliberate cost: it keeps the question set, rather than the ceremony after it, as the thing
     * that completes.
     *
     * **What reaching the end no longer does is count a set nothing was answered in.** The rule lives
     * in the repository, where it can be tested; see `markCheckInAnsweredIfAnswered`.
     */
    fun finish() {
        val current = _state.value
        val day = current.checkInDay ?: return
        val slot = current.slot ?: return

        viewModelScope.launch {
            commitCurrent()
            repository.markCheckInAnsweredIfAnswered(day, slot, dayResolver.now())
            _state.update { it.copy(onSummary = true, fromSummary = false) }
        }
    }

    /**
     * Leaves the summary. Nothing is written and nothing is marked — [finish] already did both.
     *
     * It exists because the user asked for "some indication of completing the question set", and a
     * set that ends by the screen simply vanishing does not give one.
     */
    fun confirm() {
        viewModelScope.launch {
            commitCurrent()
            leave()
        }
    }

    /**
     * Opens one question from the summary, to correct it.
     *
     * Deliberately **not** routed through [moveTo]: that returns early when the target is already the
     * current index and nothing is dirty, which is exactly the case here — the summary is reached
     * from the last question, so tapping the last question's row would be a no-op and the summary
     * flag would never clear. There is nothing to commit on the way out of a summary anyway.
     */
    fun editFromSummary(index: Int) {
        val questions = _state.value.questions
        if (questions.isEmpty()) return
        val target = index.coerceIn(0, questions.lastIndex)
        _state.update { it.copy(index = target, onSummary = false, fromSummary = true) }
    }

    /** Returns to the summary from a question opened out of it, committing the correction. */
    fun returnToSummary() {
        viewModelScope.launch {
            commitCurrent()
            _state.update { it.copy(onSummary = true, fromSummary = false) }
        }
    }

    /**
     * Leaves without finishing: commits the question on screen, and does **not** mark the check-in
     * answered.
     *
     * Closing is always allowed and never confirmed — a flow that holds the user hostage is the one
     * they stop opening (spec §5.1). What they gave is kept; the check-in stays honestly outstanding.
     */
    fun close() {
        viewModelScope.launch {
            commitCurrent()
            leave()
        }
    }

    /**
     * Announces the exit and clears the session.
     *
     * The reset is what makes re-entry work: [load]'s guard skips a check-in that is already loaded,
     * so without it, reopening the check-in just closed would show it exactly as it was left —
     * sitting on its summary, or halfway down a set the user thought they had finished.
     */
    private suspend fun leave() {
        _exit.send(Unit)
        _state.value = CheckInUiState()
    }

    private fun moveTo(target: Int) {
        val current = _state.value
        val bounded = target.coerceIn(0, current.questions.lastIndex.coerceAtLeast(0))
        if (bounded == current.index && !current.questions[current.index].dirty) return

        viewModelScope.launch {
            commitCurrent()
            _state.update { it.copy(index = bounded) }
        }
    }

    /**
     * Writes the question on screen, if it has changed since it was last written.
     *
     * **An emptied draft deletes rather than skipping.** Returning early on "nothing to write" was
     * defect 2: `recordAnswer` is the only write path and there was no delete, so clearing an answer
     * that had already been stored left the old row in place — the screen said it was gone and
     * reopening showed it back. It has to be a delete and not a blanking: a row that exists with
     * nothing in it is the real answer *"none of these"* for a select item, so blanking would turn a
     * retraction into a met goal (`DirectionEvaluator`, scoring-cases 1.13).
     */
    private suspend fun commitCurrent() {
        val current = _state.value
        val question = current.current ?: return
        val day = current.checkInDay ?: return
        val slot = current.slot ?: return

        if (question.readOnly || !question.dirty) return

        if (!question.draft.hasContent) {
            // The day the answer belongs to, which for a sleep item is not the check-in's own day,
            // and for a carry-over is the night it was deferred from.
            repository.deleteAnswer(
                question.entry.item.id,
                question.entry.carriedOverFrom ?: AnswerDay.forCheckIn(day, slot),
            )
            _state.update { state ->
                state.copy(
                    questions = state.questions.map {
                        if (it.entry.item.id == question.entry.item.id) it.copy(dirty = false) else it
                    },
                )
            }
            return
        }

        repository.recordAnswer(question.toAnswer(day, slot), day, slot)
        _state.update { state ->
            state.copy(
                questions = state.questions.map {
                    if (it.entry.item.id == question.entry.item.id) it.copy(dirty = false) else it
                },
            )
        }
    }

    // ---- Answering ---------------------------------------------------------------------------

    fun setBool(item: QuestionUi, value: Boolean?) = answered(item) { it.copy(valueBool = value) }

    fun setNumber(item: QuestionUi, value: Double?) = answered(item) { it.copy(valueNumber = value) }

    fun setTime(item: QuestionUi, value: LocalTime?) = answered(item) { it.copy(valueTime = value) }

    fun setScale(item: QuestionUi, value: Int?) = answered(item) { it.copy(valueScale = value) }

    /**
     * "Not yet" — defer this question to the next morning's check-in.
     *
     * Tapping it again cancels the deferral. Setting any value cancels it too, which is why every
     * other setter goes through [answered]: a draft that is both deferred and answered would have to
     * be resolved arbitrarily at commit time, and arbitrary is how a silent wrong answer gets in.
     */
    fun toggleDeferred(item: QuestionUi) = update(item) {
        if (it.deferred) it.copy(deferred = false) else AnswerDraft(note = it.note, deferred = true)
    }

    fun setNote(item: QuestionUi, note: String) = update(item) { it.copy(note = note) }

    /** Single-select replaces; tapping the chosen option again clears it. */
    fun selectOne(item: QuestionUi, option: OptionId) = answered(item) {
        it.copy(
            selections = if (option in it.selections) emptySet() else setOf(option),
            noneSelected = false,
        )
    }

    /** Multi-select toggles. Nothing here knows or cares which option is "no opportunity". */
    fun toggleSelection(item: QuestionUi, option: OptionId) = answered(item) {
        it.copy(
            selections = if (option in it.selections) it.selections - option else it.selections + option,
            noneSelected = false,
        )
    }

    /**
     * "None of these" — an answered multi-select with nothing selected.
     *
     * Tapping it again clears back to silence, because "I answered nothing" and "I did not answer"
     * are both states the user is entitled to, and only one of them meets a must-not-include goal.
     */
    fun selectNone(item: QuestionUi) = update(item) {
        if (it.noneSelected) it.copy(noneSelected = false)
        else it.copy(selections = emptySet(), noneSelected = true, deferred = false)
    }

    private fun QuestionUi.toAnswer(day: LocalDate, slot: Slot): Answer {
        // Two different days, and they are not interchangeable. The answer is DATED to the day it
        // describes — yesterday, for a morning check-in. Its capture is measured against the
        // CHECK-IN it is being given in, which is why CaptureResolver.forEntry takes that instead.
        val answersDay = entry.carriedOverFrom ?: AnswerDay.forCheckIn(day, slot)
        return Answer(
            itemId = entry.item.id,
            itemVersionId = entry.version.id,
            day = answersDay,
            capture = if (draft.deferred) capture.deferred() else capture.forEntry(day, entry.carriedOverFrom),
            submittedAt = dayResolver.now(),
            valueBool = draft.valueBool,
            valueNumber = draft.valueNumber,
            valueTime = draft.valueTime,
            valueScale = draft.valueScale,
            selections = draft.selections,
            note = draft.note.takeIf { it.isNotBlank() },
        )
    }

    /** Any answer cancels a pending deferral. See [toggleDeferred]. */
    private fun answered(item: QuestionUi, change: (AnswerDraft) -> AnswerDraft) =
        update(item) { change(it).copy(deferred = false) }

    private fun update(item: QuestionUi, change: (AnswerDraft) -> AnswerDraft) {
        _state.update { state ->
            state.copy(
                questions = state.questions.map {
                    if (it.entry.item.id == item.entry.item.id) {
                        it.copy(draft = change(it.draft), dirty = true)
                    } else {
                        it
                    }
                },
            )
        }
    }
}

private val AnswerType.isSelect: Boolean
    get() = this == AnswerType.SINGLE_SELECT || this == AnswerType.MULTI_SELECT
