package com.zdredge.consistency.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Numbers as a stack of full-width options.
 *
 * **This fixes a real defect.** The stepper it replaced rendered `valueNumber ?: 0.0`, so an
 * unanswered question showed `0` — identical to a real answer of zero, which scores differently
 * (silence is excluded; a real 0 is missed against "at least 3"). Options fix it by construction:
 * nothing is selected until something is tapped, so "not answered" and "answered zero" cannot look
 * alike. Tapping the selected option again clears back to unanswered, so a mis-tap costs one tap.
 *
 * **Halves are not offered inline.** Two layouts were built and compared on the device — halves
 * among the values, and a `½` modifier applied to them — and as full-width rows both read badly:
 * `1½` looks like a value of its own rather than a refinement, and a row labelled `½` sitting among
 * `1`, `2`, `3` looks like a fourth number. Removed on that evidence, along with the switch that
 * existed to compare them.
 *
 * The consequence is worth stating, because spec §1 rests its whole argument for reporting
 * attainment on *"1.5 of 2 bottles every single day"*: **1.5 is still recordable, through "Another
 * number", just not in one tap.** `value_number` is a `Double` and always was, so nothing about the
 * data changed — only how quickly a half can be entered. If halves prove to be a daily path in
 * practice, this is the decision to revisit.
 *
 * A value outside the offered set — 12 coffees, or a half from the keypad — is appended as its own
 * selected row rather than silently not showing, because a value the screen cannot display is a
 * value the user cannot trust.
 */
@Composable
fun NumberInput(
    question: QuestionUi,
    onNumber: (QuestionUi, Double?) -> Unit,
) {
    var keypadOpen by remember { mutableStateOf(false) }
    val value = question.draft.valueNumber

    val offered = listOf(0.0, 1.0, 2.0, 3.0)
    val extra = value?.takeIf { it !in offered }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        offered.forEach { candidate ->
            AnswerOption(
                label = candidate.label(),
                selected = value == candidate,
                onClick = { onNumber(question, if (value == candidate) null else candidate) },
            )
        }

        extra?.let {
            AnswerOption(label = it.label(), selected = true, onClick = { onNumber(question, null) })
        }

        // "…" said nothing at this size. A full-width row needs words.
        AnswerOption(label = "Another number", selected = false, onClick = { keypadOpen = true })
    }

    if (keypadOpen) {
        NumberKeypad(
            initial = value,
            onDismiss = { keypadOpen = false },
            onConfirm = {
                onNumber(question, it)
                keypadOpen = false
            },
        )
    }
}

/** `2.0` reads as "2"; a half from the keypad reads as "1.5". A trailing `.0` is noise. */
private fun Double.label(): String =
    if (this % 1.0 == 0.0) "%.0f".format(this) else this.toString()

/**
 * The overflow path: an app-drawn keypad, not the system keyboard.
 *
 * Coffee could be twelve and steps targets run into the thousands, so the offered options cannot
 * cover everything — but the keyboard is reserved for notes (spec §5.6), and a stepper would take
 * twelve taps to reach twelve. A keypad is tap-first and exact at any size, and it is now also where
 * a half is entered.
 */
@Composable
private fun NumberKeypad(
    initial: Double?,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit,
) {
    var entry by remember {
        mutableStateOf(
            initial?.let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.1f".format(it) } ?: "",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter a value") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    entry.ifEmpty { "—" },
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf(".5", "0", "⌫"),
                ).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { key ->
                            OutlinedButton(
                                onClick = { entry = entry.press(key) },
                                modifier = Modifier.weight(1f),
                            ) { Text(key) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(entry.toDoubleOrNull()) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun String.press(key: String): String = when {
    key == "⌫" -> dropLast(1)
    // One half at most, and only at the end -- "1.5.5" is not a number anyone meant to type.
    key == ".5" -> if (contains('.')) this else this.ifEmpty { "0" } + ".5"
    contains('.') -> this
    else -> this + key
}
