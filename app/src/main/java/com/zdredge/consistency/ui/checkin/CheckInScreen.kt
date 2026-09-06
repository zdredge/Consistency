package com.zdredge.consistency.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The check-in.
 *
 * **Tap-first, keyboard only for the optional note** (spec §5.6). Every primary input here is a tap:
 * numbers use a stepper rather than a text field, the 1–5 scale is five buttons, times use the
 * picker. A check-in that needs the keyboard is a check-in that takes longer than sixty seconds, and
 * notification fatigue is the primary abandonment risk (spec §1).
 *
 * One scrollable list rather than one question per page: eight items paged is eight taps of
 * navigation on top of the answers themselves.
 *
 * There are no tests for this file, by design (build-order). Everything that could be *wrong* rather
 * than merely ugly lives in `:domain` and is tested there; what is left here is layout, and layout
 * is checked by looking at it.
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
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { CheckInHeader(state) }

        items(state.questions, key = { it.entry.item.id.value }) { question ->
            QuestionCard(
                question = question,
                onBool = onBool,
                onNumber = onNumber,
                onTime = onTime,
                onScale = onScale,
                onSelectOne = onSelectOne,
                onToggle = onToggle,
                onNote = onNote,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
                Button(onClick = onSubmit, modifier = Modifier.weight(2f)) { Text("Done") }
            }
        }
    }
}

/**
 * States which day is being answered, always.
 *
 * The morning check-in writes to *yesterday* (spec §3.1), and a user who does not realise that will
 * answer the wrong day's questions without ever noticing. Spec §5.6 requires the date be
 * unmistakable, so it is the largest thing on the screen rather than a subtitle.
 */
@Composable
private fun CheckInHeader(state: CheckInUiState) {
    val answersDay = state.answersDay ?: return
    val writesToAnEarlierDay = state.checkInDay != answersDay

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when (state.slot) {
                Slot.MORNING -> "Morning check-in"
                Slot.NIGHT -> "Night check-in"
                else -> "Check-in"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(answersDay.format(dayFormat), style = MaterialTheme.typography.headlineSmall)
        if (writesToAnEarlierDay) {
            Text(
                "You're answering for last night, not today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuestionUi,
    onBool: (QuestionUi, Boolean?) -> Unit,
    onNumber: (QuestionUi, Double?) -> Unit,
    onTime: (QuestionUi, LocalTime?) -> Unit,
    onScale: (QuestionUi, Int?) -> Unit,
    onSelectOne: (QuestionUi, OptionId) -> Unit,
    onToggle: (QuestionUi, OptionId) -> Unit,
    onNote: (QuestionUi, String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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

            Text(question.prompt, style = MaterialTheme.typography.titleSmall)

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

            if (!question.readOnly) {
                OutlinedTextField(
                    value = question.draft.note,
                    onValueChange = { onNote(question, it) },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Measured items are shown, never asked (spec §3.3). Steps has no value until Health Connect arrives
 * in M7, and saying so plainly is better than hiding the row and having it appear later unexplained.
 */
@Composable
private fun ReadOnlyValue(question: QuestionUi) {
    Text(
        question.draft.valueNumber?.let { "%,.0f".format(it) } ?: "Not available yet",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BoolInput(question: QuestionUi, onBool: (QuestionUi, Boolean?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** A stepper, not a text field: the keyboard is reserved for the note (spec §5.6). */
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
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )

        OutlinedButton(onClick = { onNumber(question, value + 1) }) { Text("+") }

        question.unitLabel?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        question.options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
