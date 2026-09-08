package com.zdredge.consistency.ui.checkin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zdredge.consistency.domain.model.AnswerType

/**
 * What you just recorded, at the end of the set.
 *
 * **Nothing in the spec asks for this.** §5.6 is two sentences about being tap-first and under sixty
 * seconds, and there is no review or confirmation step anywhere in the docs — this is a product
 * decision made in M4.5, on the grounds that a nine-question set which ends by the screen vanishing
 * gives no sense of having finished anything.
 *
 * **It is also the first surface that names a skipped question.** The segmented bar hints at one with
 * a hollow segment; nothing has ever said so in words. That matters because silence is a real answer
 * that scores differently from a value — an unanswered absence-goal is *excluded*, where an answered
 * empty one is a miss (spec constraint 11, scoring-cases 1.13). Skips are shown plainly and quietly:
 * no warning, no red, and Confirm is never gated on them. The product reports; it does not nag.
 *
 * Rows are left-aligned, unlike the centred question cards. A card centres because it has one focal
 * point; a list is read down its left edge, and centring nine of them would cost the scan this screen
 * exists to provide.
 */
@Composable
fun CheckInSummary(
    state: CheckInUiState,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(
                "That's everything",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // Past tense deliberately: by the time this shows, `finish` has already marked the
                // check-in answered. The button below is an acknowledgement, not the act.
                "Recorded. Tap anything to change it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                state.questions.forEachIndexed { index, question ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    SummaryRow(question, onClick = { onEdit(index) })
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(question: QuestionUi, onClick: () -> Unit) {
    val value = question.summaryValue()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            question.prompt,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value ?: "Not answered",
            style = MaterialTheme.typography.titleMedium,
            // A skip is quieter than an answer, and that is the whole of the difference. It is not
            // an error state and must not look like one.
            color = if (value == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (question.draft.note.isNotBlank()) {
            Text(
                "Note: ${question.draft.note}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The answer as a line of text, or `null` for a question that was not answered.
 *
 * **`null` comes from [AnswerDraft.isAnswered] rather than from checking the value fields here.**
 * That property already carries the one rule this screen could most easily get wrong — that a
 * multi-select answered with nothing selected *is* an answer, where a blank one is silence — and a
 * second copy of it is a second place for it to drift.
 *
 * The number branch is the other trap. Rendering `valueNumber ?: 0.0` would print `0` for a question
 * nobody answered, which is exactly the defect the stacked options were built to remove; here the
 * unanswered case has already returned above.
 */
private fun QuestionUi.summaryValue(): String? = when {
    // Measured, never asked (spec §3.3). Steps has no value until Health Connect arrives in M7.
    readOnly -> draft.valueNumber?.let { "%,.0f".format(it) } ?: "Not available yet"
    draft.deferred -> "Not yet"
    !draft.isAnswered -> null
    draft.noneSelected -> "None of these"

    answerType == AnswerType.BOOL -> if (draft.valueBool == true) "Yes" else "No"
    answerType == AnswerType.NUMBER -> draft.valueNumber?.asAnswer()
    answerType == AnswerType.TIME -> draft.valueTime?.format(timeFormat)
    answerType == AnswerType.SCALE -> draft.valueScale?.toString()

    else -> options.filter { it.id in draft.selections }
        .joinToString(", ") { it.label }
        .takeIf { it.isNotBlank() }
}
