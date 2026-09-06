package com.zdredge.consistency.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The check-in: **one question per screen**.
 *
 * M4 presented all nine as a scrolling list of cards. It worked and it felt wrong — an always-open
 * note field roughly doubled every card, nothing showed what had been answered, and Done was
 * reachable only after scrolling past everything. One question at a time gives each the whole
 * screen, and the segmented bar carries the sense of progress the list never had.
 *
 * **Next works with no answer selected**, deliberately. Skipping has to be possible because silence
 * is a real state that scores differently from an answer (spec constraint 11) — a flow that trapped
 * you until you answered would force a value where the truth is that you have none.
 *
 * Advancement is manual. Auto-advance was considered and deferred: while the number input is still a
 * stepper there is no moment where "the user has answered" is unambiguous, so a screen that advanced
 * itself would be least predictable exactly where it needed to be trusted. Phase 4's chips are what
 * make it viable later.
 *
 * There are no tests for this file, by design (`CLAUDE.md`). Everything that could be *wrong* rather
 * than merely ugly lives in `:domain`; what is left here is layout, and layout is checked by looking.
 */
@Composable
fun CheckInScreen(
    state: CheckInUiState,
    onBool: (QuestionUi, Boolean?) -> Unit,
    onNumber: (QuestionUi, Double?) -> Unit,
    onTime: (QuestionUi, LocalTime?) -> Unit,
    onScale: (QuestionUi, Int?) -> Unit,
    onSelectOne: (QuestionUi, OptionId) -> Unit,
    onToggle: (QuestionUi, OptionId) -> Unit,
    onNote: (QuestionUi, String) -> Unit,
    onDefer: (QuestionUi) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val question = state.current
    if (state.loading || question == null) {
        Column(modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            if (state.loading) CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SegmentedProgress(
            total = state.questions.size,
            current = state.index,
            answered = state.answeredIndices,
        )

        CheckInHeader(state, onLeave)

        QuestionCard(
            question = question,
            modifier = Modifier.weight(1f),
            onBool = onBool,
            onNumber = onNumber,
            onTime = onTime,
            onScale = onScale,
            onSelectOne = onSelectOne,
            onToggle = onToggle,
            onNote = onNote,
            onDefer = onDefer,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onBack,
                enabled = !state.isFirst,
                modifier = Modifier.weight(1f),
            ) { Text("Back") }

            Button(
                onClick = if (state.isLast) onFinish else onNext,
                modifier = Modifier.weight(2f),
            ) { Text(if (state.isLast) "Done" else "Next") }
        }
    }
}

/**
 * Persistent across every question, because it describes the whole session rather than any one of
 * them.
 *
 * The date is the largest thing on the screen. A morning check-in writes to *yesterday* (spec §3.1),
 * and a user who does not notice will answer the wrong day's questions without ever finding out — so
 * spec §5.6 requires it be unmistakable, not a subtitle.
 */
@Composable
private fun CheckInHeader(state: CheckInUiState, onLeave: () -> Unit) {
    val answersDay = state.answersDay ?: return

    Row(verticalAlignment = Alignment.Top) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                when (state.slot) {
                    Slot.MORNING -> "Morning check-in"
                    Slot.NIGHT -> "Night check-in"
                    else -> "Check-in"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(answersDay.format(dayFormat), style = MaterialTheme.typography.headlineMedium)
            if (state.checkInDay != answersDay) {
                Text(
                    "You're answering for last night, not today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Leaving is always available and never confirmed. Answers are already written, and a flow
        // that holds the user hostage is the one they stop opening (spec §5.1).
        TextButton(onClick = onLeave) { Text("Close") }
    }
}

@Composable
private fun QuestionCard(
    question: QuestionUi,
    modifier: Modifier,
    onBool: (QuestionUi, Boolean?) -> Unit,
    onNumber: (QuestionUi, Double?) -> Unit,
    onTime: (QuestionUi, LocalTime?) -> Unit,
    onScale: (QuestionUi, Int?) -> Unit,
    onSelectOne: (QuestionUi, OptionId) -> Unit,
    onToggle: (QuestionUi, OptionId) -> Unit,
    onNote: (QuestionUi, String) -> Unit,
    onDefer: (QuestionUi) -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            // Explicit, because a surfaceVariant container defaults its content to onSurfaceVariant
            // -- the muted grey meant for labels. That left the question prompt, the one thing the
            // card exists to show, quieter than the note button under it.
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            question.entry.carriedOverFrom?.let { deferredFrom ->
                // The promise "not yet" made was that this comes back, labelled as belonging to the
                // night it was deferred from (spec §3.2, §5.6).
                Text(
                    "Carried over from ${deferredFrom.format(dayFormat)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // The prompt and its answer sit as one group a little above centre, rather than pinned
            // to the top with a void beneath. Optical centre is higher than true centre, hence the
            // heavier weight below than above.
            Spacer(Modifier.weight(1f))

            Text(question.prompt, style = MaterialTheme.typography.headlineSmall)

            Spacer(Modifier.height(20.dp))

            when {
                question.readOnly -> ReadOnlyValue(question)
                question.answerType == AnswerType.BOOL -> BoolInput(question, onBool)
                question.answerType == AnswerType.NUMBER -> NumberInput(question, onNumber)
                question.answerType == AnswerType.TIME -> TimeInput(question, onTime)
                question.answerType == AnswerType.SCALE -> ScaleInput(question, onScale)
                question.answerType == AnswerType.SINGLE_SELECT ->
                    SelectInput(question, singleChoice = true, onSelectOne, onToggle)
                question.answerType == AnswerType.MULTI_SELECT ->
                    SelectInput(question, singleChoice = false, onSelectOne, onToggle)
            }

            Spacer(Modifier.weight(1.3f))

            if (!question.readOnly) {
                NoteField(question, onNote)
            }

            if (question.entry.canDefer) {
                // Below the answers and quieter than them: "not yet" is a deferral, not a value. It
                // is still a real answer — the check-in counts as completed, and the deferral becomes
                // a missed goal only if it is never resolved (A2.1, A2.2).
                FilterChip(
                    selected = question.draft.deferred,
                    onClick = { onDefer(question) },
                    label = { Text("Not yet") },
                )
            }
        }
    }
}

/**
 * The note: collapsed until asked for.
 *
 * It was an always-open text field on every card in M4, roughly doubling each one for something
 * optional and rarely used. It is also the only thing in a check-in that needs the keyboard, and the
 * keyboard is what turns a sixty-second session into a longer one (spec §5.6).
 */
@Composable
private fun NoteField(question: QuestionUi, onNote: (QuestionUi, String) -> Unit) {
    var expanded by remember(question.entry.item.id) {
        mutableStateOf(question.draft.note.isNotBlank())
    }

    if (expanded) {
        OutlinedTextField(
            value = question.draft.note,
            onValueChange = { onNote(question, it) },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TextButton(onClick = { expanded = true }) { Text("Add note") }
    }
}

/**
 * Measured items are shown, never asked (spec §3.3). Steps has no value until Health Connect arrives
 * in M7, and saying so plainly beats hiding the row and having it appear later unexplained.
 */
@Composable
private fun ReadOnlyValue(question: QuestionUi) {
    Text(
        question.draft.valueNumber?.let { "%,.0f".format(it) } ?: "Not available yet",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BoolInput(question: QuestionUi, onBool: (QuestionUi, Boolean?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Tapping the selected answer again clears it, so a mis-tap is one tap to undo rather than
        // an answer the user cannot take back.
        FilterChip(
            selected = question.draft.valueBool == true,
            onClick = { onBool(question, if (question.draft.valueBool == true) null else true) },
            label = { Text("Yes") },
        )
        FilterChip(
            selected = question.draft.valueBool == false,
            onClick = { onBool(question, if (question.draft.valueBool == false) null else false) },
            label = { Text("No") },
        )
    }
}

/** Still a stepper at Phase 3 — chips replace it in Phase 4, along with the zero-versus-blank fix. */
@Composable
private fun NumberInput(question: QuestionUi, onNumber: (QuestionUi, Double?) -> Unit) {
    val value = question.draft.valueNumber ?: 0.0

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onNumber(question, (value - 1).coerceAtLeast(0.0)) },
            enabled = value > 0,
        ) { Text("−") }

        Text(
            text = "%,.0f".format(value),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp),
        )

        OutlinedButton(onClick = { onNumber(question, value + 1) }) { Text("+") }

        question.unitLabel?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Material3's [TimePicker] is still an experimental API. Opting in rather than hand-rolling a clock
 * face or wrapping the old View-based dialog: the Compose version is pinned by the BOM, so it cannot
 * shift underneath this build, and a time picker is not a thing worth writing twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeInput(question: QuestionUi, onTime: (QuestionUi, LocalTime?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val current = question.draft.valueTime

    OutlinedButton(onClick = { picking = true }) {
        Text(current?.format(timeFormat) ?: "Set time")
    }

    if (picking) {
        val picker = rememberTimePickerState(
            initialHour = current?.hour ?: 22,
            initialMinute = current?.minute ?: 0,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    onTime(question, LocalTime.of(picker.hour, picker.minute))
                    picking = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            text = { TimePicker(state = picker) },
        )
    }
}

@Composable
private fun ScaleInput(question: QuestionUi, onScale: (QuestionUi, Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (n in 1..5) {
            FilterChip(
                selected = question.draft.valueScale == n,
                onClick = { onScale(question, if (question.draft.valueScale == n) null else n) },
                label = { Text("$n") },
            )
        }
    }
}

/**
 * Select inputs.
 *
 * **Nothing here knows which option means "no opportunity."** It is an ordinary choice on the list,
 * and its neutral scoring lives entirely in `:domain` (spec constraint 17). Special-casing it in the
 * UI would be the first step toward it looking like an excuse rather than an answer.
 */
@Composable
private fun SelectInput(
    question: QuestionUi,
    singleChoice: Boolean,
    onSelectOne: (QuestionUi, OptionId) -> Unit,
    onToggle: (QuestionUi, OptionId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        question.options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { option ->
                    FilterChip(
                        selected = option.id in question.draft.selections,
                        onClick = {
                            if (singleChoice) onSelectOne(question, option.id)
                            else onToggle(question, option.id)
                        },
                        label = { Text(option.label) },
                    )
                }
            }
        }
    }
}
