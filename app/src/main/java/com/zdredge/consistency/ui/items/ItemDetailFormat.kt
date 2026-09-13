package com.zdredge.consistency.ui.items

import com.zdredge.consistency.domain.detail.DayCell
import com.zdredge.consistency.domain.detail.DayState
import com.zdredge.consistency.domain.detail.DayValue
import com.zdredge.consistency.domain.detail.ItemView
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.ClockAxis
import com.zdredge.consistency.ui.checkin.asAnswer
import com.zdredge.consistency.ui.checkin.timeFormat
import java.time.format.DateTimeFormatter

/**
 * How an item's history is written down.
 *
 * Formatting only — every judgement was made in `:domain` and arrives here already decided. Kept out
 * of the composables for the same reason `AnswerFormat` exists: the figures and the table describe
 * the same days, and two renderings of one stored value is how they come to disagree.
 *
 * It reads `AnswerFormat`'s own helpers rather than restating them, so a bottle-and-a-half prints
 * the same way here as it does on the check-in that recorded it.
 */

internal val tableDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * When an item is asked and whether it is scored — "Night · Goal", "Measured · Goal".
 *
 * Shared by the list and the item's own header so the two cannot describe the same item differently.
 */
internal fun subtitleFor(kind: ItemKind, slot: Slot, classification: Classification): String {
    // A measured item has no slot -- it is never asked (spec §3.3) -- so printing "Not asked" for it
    // would be true and useless.
    val asked = if (kind == ItemKind.MEASURED) {
        "Measured"
    } else {
        when (slot) {
            Slot.MORNING -> "Morning"
            Slot.NIGHT -> "Night"
            Slot.WEEKLY -> "Weekly"
            Slot.NONE -> "Not asked"
        }
    }
    val judged = when (classification) {
        Classification.GOAL -> "Goal"
        Classification.OBSERVATION -> "Observation"
    }
    return "$asked · $judged"
}

internal val windowDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * What a day amounts to, in a word.
 *
 * The four kinds of blank keep their own words. "Not answered" and "Open" are the pair that matters:
 * one is a day that went by, the other a day still in hand, and collapsing them would put failures on
 * the screen that have not happened.
 */
internal fun DayState.label(): String = when (this) {
    DayState.NOT_ACTIVE -> "Not active"
    DayState.FUTURE -> "To come"
    DayState.OPEN -> "Open"
    DayState.NOT_ANSWERED -> "Not answered"
    DayState.DEFERRED -> "Not yet"
    DayState.NO_OPPORTUNITY -> "No opportunity"
    DayState.RECORDED -> "Recorded"
    DayState.MET -> "Met"
    DayState.MISSED -> "Missed"
    DayState.CONFLICTED -> "Two sources"
}

/** The answer itself. Empty when the day has none, or when it has one that must not be drawn. */
internal fun DayValue?.text(labels: Map<OptionId, String>): String = when (this) {
    null -> ""
    is DayValue.Amount -> value.asAnswer()
    is DayValue.YesNo -> if (value) "Yes" else "No"
    is DayValue.TimeOfDay -> value.format(timeFormat)
    is DayValue.Rating -> value.toString()
    // Sorted by label so two nights with the same activities read identically rather than in
    // whatever order the set iterates.
    is DayValue.Choices ->
        if (options.isEmpty()) "None of these"
        else options.mapNotNull { labels[it] }.sorted().joinToString(", ")
}

/**
 * How the answer was recorded, where it differs from the ordinary.
 *
 * Spec §5.4 puts backfilled and deferred days on the chart and leaves late answers and edits to the
 * table — this is the table, so it carries all of them. An answer can be both backfilled and later
 * edited (spec constraint 4), which is why these are listed rather than reduced to one word.
 */
internal fun DayCell.markText(): String = buildList {
    if (marks.backfilled) add("backfilled")
    if (marks.late) add("late")
    if (marks.edited) add("edited")
    if (marks.provisional) add("provisional")
    if (marks.hasNote) add("note")
}.joinToString(" · ")

/** A percentage, or a dash. **Null is not zero** — it means nothing was scored (10.5, 11.7). */
internal fun Double?.asPercent(): String =
    this?.let { "${Math.round(it * 100)}%" } ?: "—"

/** A position on the 04:00 axis, back as a time of day. */
internal fun Double.asClockTime(): String = ClockAxis.timeAt(this).format(timeFormat)

/**
 * What the chart will be, named rather than drawn.
 *
 * Phase 3 has the figures and the table and no charts. Naming the view that is coming is worth the
 * line: it is the one place a device pass can confirm the view rule picked what §5.4 agreed, before
 * any drawing code exists to be blamed for it.
 */
internal fun ItemView.name(): String = when (this) {
    ItemView.ClockDots -> "Dots on a clock axis"
    is ItemView.ActivityRows -> "One row per activity"
    is ItemView.DayCalendar -> if (weeklyCount) "Calendar, with a weekly count" else "Calendar"
    is ItemView.ShadedCalendar -> "Calendar shaded by amount"
    is ItemView.DailyBars -> if (weeklyTotals) "Daily bars, with weekly totals" else "Daily bars"
    ItemView.WeekSquares -> "One square per week"
    ItemView.StepBars -> "Step bars"
}
