package com.zdredge.consistency.ui.checkin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Slot
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.drop

private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM")

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
    onSelectNone: (QuestionUi) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onEdit: (Int) -> Unit,
    onReturnToSummary: () -> Unit,
    onConfirm: () -> Unit,
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
            // No current segment on the summary: there is no question in hand, so the bar reads
            // purely as answered-versus-skipped, which is what it is worth on that page.
            current = if (state.onSummary) -1 else state.index,
            answered = state.answeredIndices,
        )

        CheckInHeader(state, onLeave)

        if (state.onSummary) {
            CheckInSummary(
                state = state,
                onEdit = onEdit,
                modifier = Modifier.weight(1f),
            )
        } else {
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
                onSelectNone = onSelectNone,
            )
        }

        when {
            state.onSummary ->
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Confirm") }

            // Entry decides exit. A question opened from the summary gets one unambiguous way out
            // rather than Back and Next quietly meaning something else than they did a screen ago.
            state.fromSummary ->
                Button(onClick = onReturnToSummary, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to summary")
                }

            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Slot.MORNING -> "Morning Check-in"
                    Slot.NIGHT -> "Nightly Check-in"
                    else -> "Check-in"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The date itself is the whole signal, and it is the largest thing on the screen. A line
            // under it saying "you're answering for last night" was true of the check-in and false
            // of half the questions in it -- "what time did you wake up" is asked in the morning
            // about the morning. A caption that has to be mentally discarded on some cards is worse
            // than none, and spec section 5.6 asks for the day to be unmistakable, which the
            // headline does on its own.
            Text(answersDay.format(dayFormat), style = MaterialTheme.typography.headlineMedium)
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
    onSelectNone: (QuestionUi) -> Unit,
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
        // Three bands: a fixed prompt, a scrolling middle, a fixed footer. The whole card used to
        // scroll, with Modifier.weight inside that scroll pushing the footer down -- which only
        // looked right because the content was short. Weights in a scrollable column are unreliable,
        // the column having unbounded height, and eight stacked options are not short. Fixing the
        // frame is also better on its own terms: the question stays on screen while a long list of
        // answers scrolls under it, rather than scrolling away from the thing being answered.
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            question.entry.carriedOverFrom?.let { deferredFrom ->
                // The promise "not yet" made was that this comes back, labelled as belonging to the
                // night it was deferred from (spec §3.2, §5.6).
                Text(
                    "Carried over from ${deferredFrom.format(dayFormat)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(
                question.prompt,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            // Centred in the band, so the blank space splits evenly above and below whatever the
            // answers are. Top-aligned left a screen of dead space under a two-option question;
            // bottom-aligned just moved the same gap above it. A long list still fills the band and
            // scrolls from the top, because the Column is then as tall as the Box and the alignment
            // has nothing left to do.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                when {
                    question.readOnly -> ReadOnlyValue(question)
                    question.answerType == AnswerType.BOOL -> BoolInput(question, onBool)
                    question.answerType == AnswerType.NUMBER -> NumberInput(question, onNumber)
                    question.answerType == AnswerType.TIME -> TimeInput(question, onTime)
                    question.answerType == AnswerType.SCALE -> ScaleInput(question, onScale)
                    question.answerType == AnswerType.SINGLE_SELECT ->
                        SelectInput(question, singleChoice = true, onSelectOne, onToggle, onSelectNone)
                    question.answerType == AnswerType.MULTI_SELECT ->
                        SelectInput(question, singleChoice = false, onSelectOne, onToggle, onSelectNone)
                }
                }
            }

            Spacer(Modifier.height(12.dp))

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
 * The note: collapsed until asked for, and edited somewhere the keyboard cannot cover.
 *
 * It was an always-open text field on every card in M4, roughly doubling each one for something
 * optional and rarely used. It is also the only thing in a check-in that needs the keyboard, and the
 * keyboard is what turns a sixty-second session into a longer one (spec §5.6).
 *
 * **Expanding it in place did not work.** The note sits in the card's fixed footer, at the bottom of
 * a full-height screen — which is exactly where the keyboard opens, so the user was typing into a
 * field they could not see. Moving to a dialog is the fix rather than a workaround: the dialog gets
 * its own window, which the platform resizes around the IME, so the field is above the keys by
 * construction instead of by a padding calculation that has to be right on every device.
 *
 * Once a note exists it is shown under a **Notes** heading rather than replacing the button's label.
 * Standing alone, a line of the user's own words at the foot of the card looked like part of the
 * question — the heading is what makes it read as a note, and it also keeps the section findable
 * instead of disappearing the moment it has content.
 */
@Composable
private fun NoteField(question: QuestionUi, onNote: (QuestionUi, String) -> Unit) {
    var editing by remember(question.entry.item.id) { mutableStateOf(false) }
    val note = question.draft.note

    if (note.isBlank()) {
        TextButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Add note", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    } else {
        // Not a TextButton: that would tint the note itself with the accent, and the user's own
        // words are content, not a control.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = true }
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Notes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                // Bounded, because the footer is fixed and a long note would otherwise eat the
                // answers. The whole note is still there to read and edit in the dialog.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (editing) {
        NoteDialog(question, onNote, onDone = { editing = false })
    }
}

/**
 * The note editor.
 *
 * Text is committed to the draft on every keystroke, exactly as the inline field did, so there is
 * nothing here to save and nothing to lose — which is why there is a Done and no Cancel. A Cancel
 * that could not actually undo anything would be a lie.
 *
 * Dismissing is not wired to the keyboard going away. That was the suggested behaviour and it reads
 * badly in the one case it matters: putting the keyboard down to re-read what you wrote would close
 * the thing you were reading.
 */
@Composable
private fun NoteDialog(
    question: QuestionUi,
    onNote: (QuestionUi, String) -> Unit,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Opened deliberately, so it opens ready to type. The extra tap on the field is pure friction
    // when tapping "Add note" already said what the user wants.
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Notes") },
        text = {
            OutlinedTextField(
                value = question.draft.note,
                onValueChange = { onNote(question, it) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Anything worth remembering") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
            )
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Tapping the selected answer again clears it, so a mis-tap is one tap to undo rather than
        // an answer the user cannot take back.
        AnswerOption(
            label = "Yes",
            selected = question.draft.valueBool == true,
            onClick = { onBool(question, if (question.draft.valueBool == true) null else true) },
        )
        AnswerOption(
            label = "No",
            selected = question.draft.valueBool == false,
            onClick = { onBool(question, if (question.draft.valueBool == false) null else false) },
        )
    }
}

/** Where an untouched dial starts. See [TimeDial] for why this is one value and not three. */
private val DefaultTimeAnswer: LocalTime = LocalTime.of(7, 0)

/** Room for the time and the Reset button stacked under the dial, reserved in both states. */
private val ReadoutHeight = 92.dp

/**
 * Time: the clock is the question.
 *
 * It used to be a button reading "Set time" that opened the picker in a dialog — two taps and a
 * context switch before the user could answer, where every other answer type takes one tap on
 * something already on screen. The dial is now the answer area itself, so a time question has the
 * same shape as the rest of the set.
 *
 * Keyed on the item because [rememberTimePickerState] has no keys of its own: without this, moving
 * from "went to bed" to "woke up" would reuse the slot and show the previous question's dial.
 */
@Composable
private fun TimeInput(question: QuestionUi, onTime: (QuestionUi, LocalTime?) -> Unit) {
    // Reset works by rebuilding the dial rather than by assigning to it. Both are possible --
    // `hour` and `minute` are settable -- but a programmatic write is indistinguishable, to the
    // observer in TimeDial, from the user moving the hands, so it would immediately record 00:00 as
    // an answer and undo the very thing Reset is for. A fresh state starts the observer fresh too.
    var resets by remember(question.entry.item.id) { mutableIntStateOf(0) }

    key(question.entry.item.id, resets) {
        TimeDial(
            question = question,
            start = when {
                resets > 0 -> LocalTime.MIDNIGHT
                else -> question.draft.valueTime ?: DefaultTimeAnswer
            },
            onTime = onTime,
            onReset = {
                onTime(question, null)
                resets++
            },
        )
    }
}

/**
 * Material3's [TimePicker] is still an experimental API. Opting in rather than hand-rolling a clock
 * face or wrapping the old View-based dialog: the Compose version is pinned by the BOM, so it cannot
 * shift underneath this build, and a time picker is not a thing worth writing twice.
 *
 * **An always-visible dial has to show a time, and a shown time must not look like a given one.**
 * That is the same trap the number stepper fell into — it rendered `0` for an unanswered question,
 * indistinguishable from a real zero, which scores differently. Here the dial displays
 * [DefaultTimeAnswer] until it is touched, but nothing is recorded until then, and the line beneath
 * says which state you are in: an invitation while the answer is empty, a Clear button once it is
 * not. The dial's position is a starting point, never a value.
 *
 * **Recording takes two mechanisms, because neither alone is correct.**
 *
 * Watching the value alone drops a real answer: a user whose wake time really is the 07:00 the dial
 * already shows taps `7`, nothing changes, and nothing is recorded — the same silent-silence bug the
 * number stepper had. So a pointer release records too, since a tap is intent whether or not it
 * moves the hands.
 *
 * But release alone records the *wrong* time. The picker commits its new hour after the gesture
 * finishes, so reading it on release — even on the Final pass — is one interaction stale. Tapping
 * `9` on a dial showing `7` recorded 7:00. That was on the device, not in theory: the first version
 * of this did exactly that, and the tap-the-same-value test passed only because the value happened
 * not to change, which proved a release fires and proved nothing about the value read.
 *
 * Together they are right. Tapping an unchanged value records it on release; tapping a new one
 * records the stale value for a frame and then the observer corrects it. Nothing is written to
 * storage until the question is left, so the intermediate is never durable.
 *
 * The one value that starts wrong is bedtime, which opens at 07:00 like the other two. A per-item
 * default is the fix and it has nowhere to live yet — item configuration is spec §5.7, still
 * unassigned to a milestone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDial(
    question: QuestionUi,
    start: LocalTime,
    onTime: (QuestionUi, LocalTime?) -> Unit,
    onReset: () -> Unit,
) {
    val current = question.draft.valueTime
    val picker = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = false,
    )

    // Re-read on every recomposition. Captured once, the handlers below would go on calling back
    // with the question this input first rendered.
    val record by rememberUpdatedState {
        onTime(question, LocalTime.of(picker.hour, picker.minute))
    }

    // The authoritative half: whatever the hands end up on is the answer. `drop(1)` skips the
    // value the dial merely opened at, which nobody has chosen yet.
    LaunchedEffect(picker) {
        snapshotFlow { picker.hour to picker.minute }.drop(1).collect { record() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            // The other half: a tap that selects what is already selected emits no change above, so
            // it would otherwise be lost. Final pass and nothing consumed -- this only watches.
            modifier = Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.type == PointerEventType.Release) record()
                    }
                }
            },
        ) {
            TimePicker(
                state = picker,
                // Vertical explicitly: left to itself the layout switches on available height, so
                // the same question would look different depending on how tall the card happened to
                // be. Selected is the same accent fill the answer bars use.
                layoutType = TimePickerLayoutType.Vertical,
                colors = TimePickerDefaults.colors(
                    clockDialColor = MaterialTheme.colorScheme.surface,
                    selectorColor = MaterialTheme.colorScheme.primary,
                    periodSelectorSelectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    periodSelectorSelectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }

        // Fixed height, and tall enough for the two stacked rows, so the dial sits at the same place
        // whether or not a time has been given. Sized to the answered state because that is the
        // taller of the two.
        Box(
            modifier = Modifier.fillMaxWidth().height(ReadoutHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (current == null) {
                Text(
                    "Tap the clock to record a time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(current.format(timeFormat), style = MaterialTheme.typography.titleMedium)
                    // Puts the dial back to midnight and takes the answer back to unrecorded. It
                    // does not store 00:00: that would be an answer of "midnight", and there would
                    // then be no way back to having given none -- which is a real state, and scores
                    // differently from any time at all (spec constraint 11).
                    TextButton(onClick = onReset) { Text("Reset") }
                }
            }
        }
    }
}

/**
 * The 1–5 scale, stacked like everything else.
 *
 * It costs something: a scale reads as a spectrum, and a vertical stack loses the left-to-right
 * sense of low-to-high. Accepted deliberately — in a flow meant to take sixty seconds, a question
 * whose shape is predictable is worth more than one question reading optimally.
 */
@Composable
private fun ScaleInput(question: QuestionUi, onScale: (QuestionUi, Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (n in 1..5) {
            AnswerOption(
                label = "$n",
                selected = question.draft.valueScale == n,
                onClick = { onScale(question, if (question.draft.valueScale == n) null else n) },
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
    onSelectNone: (QuestionUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        question.options.forEach { option ->
            AnswerOption(
                label = option.label,
                selected = option.id in question.draft.selections,
                onClick = {
                    if (singleChoice) onSelectOne(question, option.id)
                    else onToggle(question, option.id)
                },
            )
        }

        if (!singleChoice) {
            // Multi-select only. "None of these" is a real answer that MEETS a must-not-include goal,
            // where leaving the question blank is silence and is excluded instead. Single-select
            // needs no equivalent -- not choosing one already says nothing, and there is no option
            // list it could be an absence from.
            AnswerOption(
                label = "None of these",
                selected = question.draft.noneSelected,
                onClick = { onSelectNone(question) },
            )
        }
    }
}
