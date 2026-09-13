package com.zdredge.consistency.ui.items

import com.zdredge.consistency.domain.detail.DayState
import com.zdredge.consistency.domain.detail.GoalFigures
import com.zdredge.consistency.domain.detail.ItemDetail
import com.zdredge.consistency.domain.detail.ItemHistory
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Slot

/**
 * An assembled [ItemDetail] into the strings the screen draws.
 *
 * **A function rather than a method**, so a preview can run the real pipeline — an invented history
 * through `ItemDetails.assemble` and then through this — instead of a hand-built state that agrees
 * with the screen by construction. `:app` has no tests, so the previews are the only check these
 * screens get, and a preview that cannot reach the real code is only checking the layout.
 */
internal fun presentItemDetail(history: ItemHistory, detail: ItemDetail): ItemDetailUiState {
    val labels = history.options.associate { it.id to it.label }

    return ItemDetailUiState(
        loading = false,
        prompt = detail.version.prompt,
        subtitle = subtitleFor(detail.item.kind, detail.version.slot, detail.version.classification),
        windowLabel = "Last ${detail.figures.windowDays.size} days · " +
            "${detail.figures.windowDays.first().format(windowDayFormat)} – " +
            "${detail.figures.windowDays.last().format(windowDayFormat)}",
        chartToCome = detail.view.name(),
        figures = figuresFor(detail),
        rows = rowsFor(detail, labels),
    )
}

/**
 * The numbers, in the order spec §5.4 asks for them.
 *
 * **Hit rate and average attainment sit side by side and are never merged** (constraint 16). The pair
 * is the point on a numeric goal: hit rate is how often the target was met, attainment is how close
 * on the days it was not, and 1.5 bottles every day for a fortnight is 0% of the first and 75% of the
 * second. Reporting either alone is a lie in one direction or the other.
 */
private fun figuresFor(detail: ItemDetail): List<Figure> = buildList {
    detail.figures.daily?.let { addAll(goalFigures(it, "day", "days")) }
    detail.figures.weekly?.let { addAll(goalFigures(it, "week", "weeks")) }

    // Constraint 17: leaning on the neutral answer stays legible rather than hidden.
    val noOpportunity = (detail.figures.daily ?: detail.figures.weekly)?.summary?.noOpportunityCount ?: 0
    if (noOpportunity > 0) {
        add(Figure("No opportunity", "$noOpportunity", "chosen in the window"))
    }

    detail.figures.recording?.let { run ->
        // "Recorded", never "streak". These are the items deliberately never judged, and a streak
        // label would turn the one unscored thing in the app into a performance measure.
        val unit = if (detail.version.slot == Slot.MORNING) "Nights" else "Days"
        add(Figure("$unit recorded in a row", "${run.longest}", "now ${run.current}"))
    }

    detail.figures.typicalMinute?.let {
        add(Figure("Typical time", it.asClockTime(), "over the nights shown"))
    }

    if (isEmpty()) {
        // An observation with nothing recorded yet, which is most of the library on day three.
        add(Figure("Nothing to report yet", "—", "figures appear as answers arrive"))
    }
}

private fun goalFigures(figures: GoalFigures, unit: String, units: String): List<Figure> {
    val summary = figures.summary
    val scored = summary.met + summary.missed
    return buildList {
        add(
            Figure(
                label = "Hit rate, by the $unit",
                value = summary.hitRate.asPercent(),
                // Never the percentage alone: 2 of 3 and 200 of 300 are the same number and not the
                // same fact, and on the first fortnight of an item the denominator is the story.
                detail = if (scored == 0) "nothing scored yet" else "${summary.met} of $scored $units",
            ),
        )
        if (summary.averageAttainment != null) {
            add(Figure("Average attainment", summary.averageAttainment.asPercent(), "how close, on average"))
        }
        // Named by period like the hit rate above it. Coffee and steps show both sets at once, and
        // two rows reading "Longest run" differ only by the unit on their value.
        add(
            Figure(
                "Longest run, by the $unit",
                "${figures.run.longest} $units",
                "now ${figures.run.current}",
            ),
        )
    }
}

/**
 * The table — every state, in bulk, most recent first (spec §5.4).
 *
 * Days the item did not exist on and days that have not arrived are left out. They are real states
 * and the chart needs them, because a calendar has to draw a square for every day in its grid; a
 * table does not, and thirty rows reading "not active" would bury the three that say something.
 */
private fun rowsFor(detail: ItemDetail, labels: Map<OptionId, String>): List<HistoryRow> =
    detail.days
        .asReversed()
        .filterNot { it.state == DayState.NOT_ACTIVE || it.state == DayState.FUTURE }
        .map { cell ->
            HistoryRow(
                day = cell.day.format(tableDayFormat),
                state = cell.state.label(),
                value = cell.value.text(labels),
                marks = cell.markText(),
                note = cell.note,
            )
        }
