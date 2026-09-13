package com.zdredge.consistency.ui.items.chart

import androidx.compose.ui.graphics.Color

/**
 * The chart palette, exactly as agreed over three rounds of mockups on 2026-09-11.
 *
 * **These values are decisions, not defaults.** Every ramp was validated as an ordinal scale on the
 * app's dark surface before it was shown, and the two that came back with notes were redrawn until
 * the user agreed. Changing one means re-deriving it, not nudging a hex.
 *
 * The theme's own palette carries the app's voice — one accent doing all the emphasis. These are the
 * additions that only charts need, and they are here rather than in `Color.kt` because nothing else
 * in the app may reach for them: a second red loose in the UI is exactly what the theme's single
 * accent exists to prevent.
 */
internal object ChartPalette {

    /**
     * A missed day. **Grey, not red** — spec §5.4, by explicit decision.
     *
     * The product does not scold. A month of grey squares is legible as a month of grey squares; a
     * month of red ones is a telling-off, and the user stops opening the app rather than stops
     * missing days.
     */
    val Missed = Color(0xFF6B7380)

    /** A no-opportunity day: a dim fill and a dash through it, so leaning on it is visible. */
    val NoOpportunity = Color(0xFF2E333B)

    /** The outline of a day that has not arrived. Present, and barely. */
    val Grid = Color(0xFF262A31)

    /** The fill behind a met week's tally chip. */
    val Tint = Color(0xFF1E2A3D)

    /** Activities other than the flagged one, on the before-bed rows. */
    val Quiet = Color(0xFF4A515C)

    /**
     * *Scrolled on phone*, and nothing else in the app.
     *
     * The user asked for it in red. `Rust` — the theme's existing red — failed the chroma floor at
     * this size and read as grey, so this is a new colour, validated against the accent at normal
     * vision ΔE 26 and deuteranopia ΔE 21. `Rust` stays reserved for genuine failure.
     */
    val Flagged = Color(0xFFD9645C)

    /**
     * Meals: four shades for 0–1, 2, 3, 4+.
     *
     * **The largest step is between 2 and 3**, because that is where the answers cluster and where
     * the target sits. A flat five-step ramp put the difference that matters on the faintest step of
     * the chart, which is what round 2 came back saying.
     */
    val MealsRamp = listOf(
        Color(0xFF3A5078),
        Color(0xFF4A6DA3),
        Color(0xFF88AFF0),
        Color(0xFFC4D9FC),
    )

    /** Water: three shades for 0–1, 2, 3+. Whole bottles, since that is what the buttons offer. */
    val WaterRamp = listOf(
        Color(0xFF3F5A87),
        Color(0xFF6E98DA),
        Color(0xFFB8D0FB),
    )

    /**
     * Mindset, 1 to 5: dim for very negative through bright for very positive.
     *
     * **One blue, never red-to-blue.** A warm end would paint low days as warnings, and mindset is
     * the one item the app never scores.
     */
    val MindsetRamp = listOf(
        Color(0xFF3B5378),
        Color(0xFF4A6FA8),
        Color(0xFF6090D6),
        Color(0xFF86AEF0),
        Color(0xFFB8D0FB),
    )

    /**
     * The ramp for a scale of [size] shades.
     *
     * Meals and water are the target-anchored scales and mindset is the 1–5 one; they differ in
     * length, which is enough to tell them apart. A scale of some other length — which only a
     * settings-added item could produce — takes an evenly spaced slice of the mindset ramp rather
     * than failing to draw.
     */
    fun rampOf(size: Int): List<Color> = when (size) {
        MealsRamp.size -> MealsRamp
        WaterRamp.size -> WaterRamp
        MindsetRamp.size -> MindsetRamp
        else -> List(size) { MindsetRamp[(it * (MindsetRamp.size - 1)) / (size - 1).coerceAtLeast(1)] }
    }
}
