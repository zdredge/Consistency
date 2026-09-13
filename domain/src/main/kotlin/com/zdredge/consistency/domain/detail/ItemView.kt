package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target

/** How one item's history is drawn. Spec §5.4 fixes one of these per item. */
sealed interface ItemView {

    /** Times of day on an axis that starts at 04:00 — bedtime, waking, getting up. */
    data object ClockDots : ItemView

    /** One row per option, with the option a target forbids picked out. */
    data class ActivityRows(val flaggedOption: OptionId?) : ItemView

    /** A square per day. [weeklyCount] adds each week's tally against its target. */
    data class DayCalendar(val weeklyCount: Boolean) : ItemView

    /** A square per day, shaded by how much. */
    data class ShadedCalendar(val scale: ShadeScale) : ItemView

    /** A bar per day. [weeklyTotals] adds each week's total against a weekly limit. */
    data class DailyBars(val weeklyTotals: Boolean) : ItemView

    /** One square per week, for a question asked once a week. */
    data object WeekSquares : ItemView

    /** Bars, with the states only a measured value has: provisional, and two sources disagreeing. */
    data object StepBars : ItemView
}

/**
 * Which view an item gets.
 *
 * **Worked out from what the item is, not looked up by name.** Spec §5.4 lists a view per item because
 * that is how they were agreed, one at a time, looking at drawings. A table of sixteen names would
 * match that list and teach nothing: the first item added through settings would fall off the end of
 * it. These rules reproduce all sixteen agreed views and give a new item a considered one.
 *
 * The order matters and is the rule, not an implementation detail. Steps is a number *and* measured;
 * the weekly questions are single-selects *and* weekly. Each is decided by the more specific fact
 * first.
 */
object ItemViews {

    /**
     * [targets] must be the targets **in force on the day being shown** — at most one per period.
     * Resolving them is the caller's job, because which targets are in force is a question about a
     * date and this is not.
     */
    fun derive(kind: ItemKind, version: ItemVersion, targets: Collection<Target>): ItemView {
        // Measured first: steps is a NUMBER with a weekly target, which would otherwise read as
        // coffee. What makes it different is that nobody answers it.
        if (kind == ItemKind.MEASURED) return ItemView.StepBars

        // Weekly slot next: a question asked once a week has at most one answer in seven, so a day
        // calendar would be six-sevenths empty whatever its answer type.
        if (version.slot == Slot.WEEKLY) return ItemView.WeekSquares

        val weekly = targets.any { it.period == Period.WEEK }

        // Exhaustive on purpose, with no `else`: a new answer type should stop the build and be given
        // a view deliberately, rather than quietly inheriting whichever branch happened to be last.
        return when (version.answerType) {
            AnswerType.TIME -> ItemView.ClockDots

            AnswerType.MULTI_SELECT -> ItemView.ActivityRows(
                flaggedOption = targets.firstOrNull { it.direction == Direction.MUST_NOT_INCLUDE }?.optionId,
            )

            AnswerType.SCALE -> ItemView.ShadedCalendar(ShadeScale.perValue(ShadeScale.SCALE_RANGE))

            AnswerType.NUMBER -> {
                val dailyFloor = targets.firstOrNull {
                    it.period == Period.DAY && it.direction == Direction.AT_LEAST && it.valueNumber != null
                }
                when {
                    // A weekly limit needs weekly totals beside the days, or the limit is invisible.
                    weekly -> ItemView.DailyBars(weeklyTotals = true)
                    // A daily floor shades well: the amounts are small and cluster around the target.
                    dailyFloor != null -> ItemView.ShadedCalendar(ShadeScale.targetAnchored(dailyFloor.valueNumber!!))
                    else -> ItemView.DailyBars(weeklyTotals = false)
                }
            }

            AnswerType.BOOL, AnswerType.SINGLE_SELECT -> ItemView.DayCalendar(weeklyCount = weekly)
        }
    }
}
