package com.zdredge.consistency.ui.checkin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** How tall a single answer sits. Generous enough to hit one-handed without looking. */
private val OptionHeight = 62.dp

/**
 * One answer, as a full-width bar.
 *
 * **Every answer type uses this** — yes/no, the 1–5 scale, both select types, and both number
 * layouts. Before, each input built its own row of `FilterChip`s, which is how four slightly
 * different treatments drifted into existence; a single component makes "all answer styles look the
 * same" true by construction rather than by discipline.
 *
 * Compact chips were the previous treatment and read as a small cluster of controls adrift in a
 * full-bleed card. A wide bar fills the card, and it is a far better target for a sixty-second
 * check-in held one-handed at 21:00 — which spec §1 says is the moment that decides whether the app
 * keeps getting opened.
 *
 * Selected is a filled accent; unselected is an outline. Same language the chips used, larger. The
 * accent does this job and no other (see `Color.kt`), so a filled row means *chosen* everywhere in
 * the app without the user having to learn it twice.
 *
 * `FilterChip` is deliberately not stretched into this shape: it is built to be compact, and getting
 * here would mean overriding most of its defaults and inheriting the rest by accident.
 */
@Composable
fun AnswerOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(OptionHeight),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
