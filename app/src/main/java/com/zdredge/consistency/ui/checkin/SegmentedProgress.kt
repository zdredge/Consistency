package com.zdredge.consistency.ui.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * One segment per question, across the top of the check-in.
 *
 * Segmented rather than continuous, and that is the whole point. A continuous bar says only *where
 * you are*; these also say **what you have answered**, which was missing entirely from the scrolling
 * version — you could reach the end with three questions silently blank and nothing on screen would
 * have said so.
 *
 * Three states, and skipped deliberately looks like an absence rather than an error. Silence is a
 * real answer here and scores differently from a value (spec constraint 11), so the design shows it
 * without scolding: a hollow segment, never a red one. The product reports; it does not nag.
 */
@Composable
fun SegmentedProgress(
    total: Int,
    current: Int,
    answered: Set<Int>,
    modifier: Modifier = Modifier,
) {
    if (total <= 0) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(total) { index ->
            val colour = when {
                index in answered -> MaterialTheme.colorScheme.primary
                index == current -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.outline
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (index == current) 5.dp else 3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colour),
            )
        }
    }
}
