package com.zdredge.consistency.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette. **Dark only, and deliberately so** (M4.5).
 *
 * Until now the app used the Android Studio scaffold's purple with `dynamicColor = true`, which
 * meant it wore whatever palette the user's wallpaper happened to produce. For a product whose value
 * is being undeniable and whose tone is deliberately blunt, handing its voice to the wallpaper was an
 * odd fit — and it made every screenshot a different colour.
 *
 * The character wanted is **soft, not loud**: a cool near-black ground, muted surfaces that separate
 * by a step of lightness rather than by borders, and one blue accent doing all the emphasis. Nothing
 * here is saturated enough to read as neon; the accent is a legible mid-light blue rather than a
 * signal colour, because in an app that reports on you every day, a bright accent stops meaning
 * anything by the second week.
 *
 * There is no light scheme. The two moments this app is used are 08:00 and 21:00, and committing to
 * one look means every surface built after this can be judged against a single ground.
 */

/** The ground. Cool rather than neutral, so the blue accent sits in the same family. */
val Ink = Color(0xFF121418)

/** Raised surfaces — the check-in card, the banner. One step up from the ground. */
val Slate = Color(0xFF191C21)

/** Interactive surfaces: chips, inputs, anything tappable but unselected. */
val Graphite = Color(0xFF23272E)

/** Hairlines and unselected chip outlines. Visible, never assertive. */
val Outline = Color(0xFF3A404A)

/** The one accent. Carries selection, progress and the primary action, and nothing else. */
val Accent = Color(0xFF8AB4F8)

/** Text and icons sitting on a filled [AccentMuted] surface — a selected answer. */
val OnAccentMuted = Color(0xFFEAF1FD)

/** Text and icons sitting on [Accent]. Dark enough to stay legible at small sizes. */
val OnAccent = Color(0xFF0A1F3D)

/**
 * A filled accent surface — a selected answer.
 *
 * Brightened from a much darker navy: at 62dp a full-width row needs to read as *chosen* from arm's
 * length in a dark room, and the first value was legible only once you were looking for it. Still
 * short of [Accent] itself, which stays reserved for the primary action so a selected answer never
 * competes with the button that moves you on.
 */
val AccentMuted = Color(0xFF3E6FB5)

/** Primary text. Not pure white: full contrast on a dark ground reads as glare. */
val Bone = Color(0xFFE4E6EA)

/** Secondary text — labels, dates, the quieter half of a card. */
val Ash = Color(0xFFA8AEB8)

/** Reserved for genuine failure. The product does not scold, so this stays rare. */
val Rust = Color(0xFFF2B8B5)
