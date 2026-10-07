package com.zdredge.consistency.ui.checkin

import java.math.RoundingMode
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * How an answer is written down, in the one place both the inputs and the summary read it from.
 *
 * The summary restates every answer the inputs collected, which makes it the obvious place for a
 * second, slightly different rendering to appear — a `19:30` next to a dial that says 7:30 PM, or a
 * `2.0` next to an option labelled 2. Sharing the formatting is what stops that, and it is cheaper
 * than noticing later that two screens disagree about the same stored value.
 */

/**
 * Twelve-hour, to match the dial.
 *
 * The dial has an AM/PM toggle, so it is a 12-hour control; printing its value as `19:30` underneath
 * made the screen speak two conventions at once about the same number.
 */
internal val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/**
 * **Every number the app shows is written here**, in one convention: grouped thousands, at most one
 * decimal, no trailing zero. `2.0` reads "2", a half from the keypad "1.5", a step count "18,191", and
 * an average of 1, 1 and 2 bottles "1.3" -- not "1.3333333333333333", which is what a bare `toString()`
 * printed on the dashboard.
 *
 * **Always the US convention, never the phone's locale.** The keypad types a "." and parses one, and
 * `GoalLine` in `:domain` writes "8,000" the same way. A screen that switched to "1,5" on a comma
 * locale would disagree with both, and the keypad would no longer read back what it showed. Dates
 * still follow the phone; numbers do not.
 *
 * Half up, so 2.25 reads "2.3" as a person would round it, not "2.2" by banker's rounding.
 */
internal fun Double.asAnswer(): String = NumberWriter.get().format(this)

/** A fraction as a whole percentage -- "75%" -- or a dash when there is nothing to report (10.5, 11.7). */
internal fun Double?.asPercent(): String = this?.let { "${(it * 100).roundToInt()}%" } ?: "—"

/** `NumberFormat` is not thread-safe, so each thread gets its own; they are all configured alike. */
private val NumberWriter: ThreadLocal<NumberFormat> = ThreadLocal.withInitial {
    NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 1
        roundingMode = RoundingMode.HALF_UP
    }
}
