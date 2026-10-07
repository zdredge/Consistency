package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.scoring.TargetResolver
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/**
 * A goal in words, built from its target: "at least 2 bottles a day", "not “Scrolled on phone”, every
 * night", "yes on at least 3 days a week".
 *
 * **Generated, never stored or hand-written** -- the user's call in the M10 review. A question and
 * its goal were not connected in their mind: "How much water did you drink?" beside a hit rate did not
 * say "drink at least 2 bottles a day". So the goal is spelled out beneath the question, from the
 * target's direction and number, the version's unit, the option labels and the slot, and an item
 * added later gets its line by the same rules with nothing to fill in.
 *
 * The word for the period follows when the question is asked: a morning question is about the night
 * before, so its daily goal is "every night".
 */
object GoalLine {

    fun of(target: Target, version: ItemVersion, options: List<SelectOption>, rollUp: RollUpSpec?): String {
        val unit = version.unitLabel?.lowercase()?.let { " $it" }.orEmpty()
        val each = when {
            target.period == Period.WEEK -> "week"
            version.slot == Slot.MORNING -> "night"
            else -> "day"
        }
        val option = target.optionId?.let { id -> options.firstOrNull { it.id == id }?.label }
        val n = target.valueNumber?.let(::number)

        // A weekly count of yes-days reads as days, not as a bare number: "3 a week" of what?
        if (target.period == Period.WEEK && rollUp?.aggregation == RollUpAggregation.COUNT_OF_YES && n != null) {
            return when (target.direction) {
                Direction.AT_MOST -> "yes on no more than $n days a week"
                Direction.EXACTLY -> "yes on exactly $n days a week"
                else -> "yes on at least $n days a week"
            }
        }

        return when (target.direction) {
            Direction.AT_LEAST -> "at least $n$unit a $each"
            Direction.AT_MOST -> "no more than $n$unit a $each"
            Direction.EXACTLY -> "exactly $n$unit a $each"
            Direction.IS_TRUE -> "yes, every $each"
            Direction.IS_FALSE -> "no, every $each"
            Direction.MUST_INCLUDE -> "“${option ?: "?"}”, every $each"
            Direction.MUST_NOT_INCLUDE -> "not “${option ?: "?"}”, every $each"
        }
    }

    /**
     * The goal line for an item on [on], or null for an observation or an item with no target.
     *
     * The daily target when there is one, else the weekly: the same choice the dashboard makes when
     * it places a goal on the trend carousel, so the line and the panel describe the same goal.
     */
    fun forItem(
        version: ItemVersion,
        targets: TargetResolver,
        options: List<SelectOption>,
        rollUp: RollUpSpec?,
        on: LocalDate,
    ): String? {
        if (version.classification != Classification.GOAL) return null
        val target = targets.resolve(version.itemId, Period.DAY, on)
            ?: targets.resolve(version.itemId, Period.WEEK, on)
            ?: return null
        return of(target, version, options.filter { it.itemId == version.itemId }, rollUp)
    }

    /**
     * The question as a heading: the prompt without a trailing parenthetical, since the goal line now
     * carries the unit -- "How much water did you drink? (Bottles)" reads "How much water did you
     * drink?". Anything else in the wording is left exactly as the user sees it on the check-in.
     */
    fun question(prompt: String): String = prompt.replace(Regex("""\s*\([^()]*\)\s*$"""), "").trim()

    /**
     * 8000.0 as "8,000"; 2.5 as "2.5". The same convention as `:app`'s `AnswerFormat.asAnswer` --
     * US, grouped, one decimal at most, half up -- so a goal line and the figure beside it agree.
     */
    private fun number(value: Double): String {
        val format = NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = 1
            roundingMode = RoundingMode.HALF_UP
        }
        return format.format(value)
    }
}
