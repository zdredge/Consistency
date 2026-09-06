package com.zdredge.consistency.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.domain.checkin.AnswerDay
import com.zdredge.consistency.domain.checkin.CaptureResolver
import com.zdredge.consistency.domain.checkin.CheckInEntry
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    /**
     * Whether the user actually answered.
     *
     * A blank draft is **silence**, and silence is never stored: an empty answer must never satisfy
     * a must-not-include goal (spec constraint 11, scoring-cases 1.13). Note that an *answered*
     * multi-select with nothing selected is a different thing entirely and is not expressible here —
     * it needs the select UI to say so, which is why it stays a Phase 5 concern along with "not yet".
     */
    val isAnswered: Boolean
        get() = valueBool != null || valueNumber != null || valueTime != null ||
            valueScale != null || selections.isNotEmpty()
}

/** One question as the screen needs it: what to ask, how to ask it, and what has been entered. */
data class QuestionUi(
    val entry: CheckInEntry,
    val options: List<SelectOption> = emptyList(),
    val draft: AnswerDraft = AnswerDraft(),
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
    val submitted: Boolean = false,
)

/**
 * The check-in screen's state holder.
 *
 * It is deliberately thin. Every decision that could be *wrong* rather than merely ugly — which day
 * an answer belongs to, which capture state it earns, which questions are asked at all — lives in
 * `:domain` and is tested there without a device. What is left here is assembly, which is why the
 * screen above it can be verified by hand.
 */
class CheckInViewModel(
    private val repository: ConsistencyRepository,
    private val dayResolver: DayResolver,
) : ViewModel() {

    private val capture = CaptureResolver(dayResolver)

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

    fun load(day: LocalDate, slot: Slot) {
        viewModelScope.launch {
            val entries = repository.checkInQuestions(day, slot)
            val questions = entries.map { entry ->
                QuestionUi(
                    entry = entry,
                    options = if (entry.version.answerType.isSelect) {
                        repository.activeOptions(entry.item.id)
                    } else {
                        emptyList()
                    },
                    draft = existingDraft(entry, day, slot),
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
     * Pre-fills from an answer already given, so opening a check-in a second time — to correct
     * something, or to finish it — shows what is there rather than a blank form. History is editable
     * and edits are never silent (spec §3.2).
     */
    private suspend fun existingDraft(
        entry: CheckInEntry,
        day: LocalDate,
        slot: Slot,
    ): AnswerDraft {
        val answersDay = entry.carriedOverFrom ?: AnswerDay.forCheckIn(day, slot)
        val existing = repository.answer(entry.item.id, answersDay) ?: return AnswerDraft()
        return AnswerDraft(
            valueBool = existing.valueBool,
            valueNumber = existing.valueNumber,
            valueTime = existing.valueTime,
            valueScale = existing.valueScale,
            selections = existing.selections,
            note = existing.note.orEmpty(),
        )
    }

    fun setBool(item: QuestionUi, value: Boolean?) = update(item) { it.copy(valueBool = value) }

    fun setNumber(item: QuestionUi, value: Double?) = update(item) { it.copy(valueNumber = value) }

    fun setTime(item: QuestionUi, value: LocalTime?) = update(item) { it.copy(valueTime = value) }

    fun setScale(item: QuestionUi, value: Int?) = update(item) { it.copy(valueScale = value) }

    fun setNote(item: QuestionUi, note: String) = update(item) { it.copy(note = note) }

    /** Single-select replaces; tapping the chosen option again clears it. */
    fun selectOne(item: QuestionUi, option: OptionId) = update(item) {
        it.copy(selections = if (option in it.selections) emptySet() else setOf(option))
    }

    /** Multi-select toggles. Nothing here knows or cares which option is "no opportunity". */
    fun toggleSelection(item: QuestionUi, option: OptionId) = update(item) {
        it.copy(
            selections = if (option in it.selections) it.selections - option else it.selections + option,
        )
    }

    /**
     * Stores every answered question and marks the check-in answered.
     *
     * **Unanswered questions are not stored.** Silence must stay silence — writing an empty row
     * would let an absence satisfy a must-not-include goal, which spec constraint 11 calls the single
     * most likely place for the scoring to be implemented wrong.
     *
     * The check-in is marked answered regardless of how much was filled in, and that is the product
     * working as designed rather than a loophole: response rate measures **showing up**, goal
     * completion measures doing. A sparse check-in scores well on the first and badly on the second,
     * which is exactly the split spec §1 is built around.
     */
    fun submit() {
        val current = _state.value
        val day = current.checkInDay ?: return
        val slot = current.slot ?: return

        viewModelScope.launch {
            for (question in current.questions) {
                if (question.readOnly || !question.draft.isAnswered) continue
                repository.recordAnswer(question.toAnswer(day, slot), day, slot)
            }
            repository.markCheckInAnswered(day, slot, dayResolver.now())
            _state.update { it.copy(submitted = true) }
        }
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
            capture = capture.forEntry(day, entry.carriedOverFrom),
            submittedAt = dayResolver.now(),
            valueBool = draft.valueBool,
            valueNumber = draft.valueNumber,
            valueTime = draft.valueTime,
            valueScale = draft.valueScale,
            selections = draft.selections,
            note = draft.note.takeIf { it.isNotBlank() },
        )
    }

    private fun update(item: QuestionUi, change: (AnswerDraft) -> AnswerDraft) {
        _state.update { state ->
            state.copy(
                questions = state.questions.map {
                    if (it.entry.item.id == item.entry.item.id) it.copy(draft = change(it.draft)) else it
                },
            )
        }
    }
}

private val AnswerType.isSelect: Boolean
    get() = this == AnswerType.SINGLE_SELECT || this == AnswerType.MULTI_SELECT
