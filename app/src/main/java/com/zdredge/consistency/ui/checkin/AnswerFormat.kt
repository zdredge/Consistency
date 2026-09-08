package com.zdredge.consistency.ui.checkin

import java.time.format.DateTimeFormatter

/**
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

/** `2.0` reads as "2"; a half from the keypad reads as "1.5". A trailing `.0` is noise. */
internal fun Double.asAnswer(): String =
    if (this % 1.0 == 0.0) "%.0f".format(this) else this.toString()
