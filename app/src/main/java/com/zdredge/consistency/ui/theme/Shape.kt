package com.zdredge.consistency.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii, rounded further than Material's defaults.
 *
 * The brief was **soft**, and softness in a dark interface comes mostly from corners: with no
 * borders and low-contrast surfaces, the radius is what a shape is read by. These run roughly one
 * step larger than the Material 3 defaults throughout.
 *
 * The one that matters most is [Shapes.large] at 28dp, used by the check-in card. A full-bleed card
 * with a tight radius reads as a panel the content is trapped in; a generous one reads as a card
 * being handed to you, which is closer to what a single question a time should feel like.
 */
val ConsistencyShapes = Shapes(
    /** Chips and small controls. */
    extraSmall = RoundedCornerShape(10.dp),
    /** Buttons, the note field. */
    small = RoundedCornerShape(14.dp),
    /** Summary rows, the outstanding-check-in banner. */
    medium = RoundedCornerShape(20.dp),
    /** The check-in card. */
    large = RoundedCornerShape(28.dp),
    /** Sheets and dialogs. */
    extraLarge = RoundedCornerShape(32.dp),
)
