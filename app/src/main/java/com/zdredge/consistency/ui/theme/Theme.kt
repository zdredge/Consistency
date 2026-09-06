package com.zdredge.consistency.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * One scheme, always dark (M4.5).
 *
 * **Dynamic colour is deliberately off.** It was on by default from the scaffold, which meant the
 * app took its palette from the user's wallpaper — pleasant Android citizenship, and wrong here: a
 * product whose value is being undeniable should not change character when the home screen does, and
 * a design that cannot be relied on cannot be designed against.
 *
 * There is no light scheme either. The app is opened at 08:00 and 21:00 by one person on one device,
 * and committing to a single ground means every surface built after this — M6's settings, M8's
 * charts, M10's rings — is judged against the same background rather than two.
 *
 * The mapping below leans on **one accent doing all the emphasis**. Selection, progress and the
 * primary action are all [Accent]; everything else separates by a step of surface lightness. In an
 * app that reports on you daily, a second signal colour would be competing for a meaning it does not
 * have.
 */
private val ConsistencyColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentMuted,
    onPrimaryContainer = Bone,

    // Secondary is intentionally not a second accent -- it is the quiet grey used for controls that
    // are tappable but not the point of the screen.
    secondary = Ash,
    onSecondary = Ink,

    // secondaryContainer is what Material fills a SELECTED chip with, so it has to carry the accent.
    // It was mapped to Graphite first, which is the card colour, and the result was a selected chip
    // indistinguishable from an unselected one -- the accent doing none of the one job it has. Caught
    // by putting it on the device, which is why the theme is the first phase of this milestone.
    secondaryContainer = AccentMuted,
    onSecondaryContainer = OnAccentMuted,

    tertiary = Ash,
    onTertiary = Ink,

    background = Ink,
    onBackground = Bone,

    surface = Slate,
    onSurface = Bone,
    surfaceVariant = Graphite,
    onSurfaceVariant = Ash,

    outline = Outline,
    outlineVariant = Outline,

    error = Rust,
    onError = Ink,
)

@Composable
fun ConsistencyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ConsistencyColors,
        typography = ConsistencyTypography,
        shapes = ConsistencyShapes,
        content = content,
    )
}
