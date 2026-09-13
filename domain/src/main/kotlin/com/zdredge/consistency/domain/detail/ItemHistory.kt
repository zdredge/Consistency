package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpSpec
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.model.Target
import com.zdredge.consistency.domain.scoring.TargetResolver
import com.zdredge.consistency.domain.scoring.inForce
import java.time.LocalDate
import java.time.LocalTime

/**
 * Everything known about one item, and nothing belonging to any other.
 *
 * **The checks in [init] are the point of this type.** The calculators underneath it take flat lists
 * and trust them: `RollUpCalculator.weekly` does not filter by item, so one stray row from a
 * neighbouring item would be silently counted into this item's week — a plausible wrong number rather
 * than a crash, which is the failure mode the whole `:domain` module exists to prevent. Gathering the
 * rows in one place means that check is written once instead of at every call site.
 *
 * Two answers on one day is the other one. It cannot happen through the schema, which keys answers on
 * (item, day), but this type can also be built by hand in tests and previews — and a duplicate would
 * make a count disagree with the calendar drawn beside it.
 */
data class ItemHistory(
    val item: Item,
    val versions: List<ItemVersion>,
    val options: List<SelectOption> = emptyList(),
    val targets: List<Target> = emptyList(),
    /** How this item's days fold into a week. Null when it declares none — never guessed (spec §3.4). */
    val rollUp: RollUpSpec? = null,
    val answers: List<Answer> = emptyList(),
    val measured: List<MeasuredValue> = emptyList(),
) {

    init {
        require(versions.isNotEmpty()) { "an item with no version has no definition to read it under" }
        requireOwned("versions", versions.map { it.itemId })
        requireOwned("options", options.map { it.itemId })
        requireOwned("targets", targets.map { it.itemId })
        requireOwned("answers", answers.map { it.itemId })
        requireOwned("measured values", measured.map { it.itemId })
        require(rollUp == null || rollUp.itemId == item.id) {
            "roll-up belongs to ${rollUp?.itemId?.value}, not ${item.id.value}"
        }
        requireOneADay("answers", answers.map { it.day })
        requireOneADay("measured values", measured.map { it.day })
    }

    /** Targets keyed by (period, date) — the resolver, built once rather than per day drawn. */
    val targetResolver: TargetResolver = TargetResolver(targets)

    private val answersByDay: Map<LocalDate, Answer> = answers.associateBy { it.day }
    private val measuredByDay: Map<LocalDate, MeasuredValue> = measured.associateBy { it.day }

    /** Spec constraint 17: choosing one of these excludes the period before any direction is applied. */
    val noOpportunityOptions: Set<OptionId> =
        options.filter { it.isNoOpportunity }.map { it.id }.toSet()

    /**
     * The times answered, by the night they belong to — the sleep chart's raw material.
     *
     * A deferral is left out. It carries no time in practice, since deferring clears the value, but
     * the trend must never be computed from a value the user explicitly declined to give.
     */
    val timesByNight: Map<LocalDate, LocalTime> = answers
        .filter { it.capture != Capture.PENDING }
        .mapNotNull { answer -> answer.valueTime?.let { answer.day to it } }
        .toMap()

    /**
     * The item as it stands at the end of its history.
     *
     * Used for the questions that are about the item *now* rather than about a day — which slot it is
     * asked in, and therefore which days it could have answers for at all.
     */
    val latestVersion: ItemVersion =
        versions.maxWith(compareBy({ it.effectiveFrom }, { it.versionNo }))

    /**
     * The version in force on [day] — never simply the latest. Spec constraint 6: an answer given
     * under version 1 is read under version 1 for ever, whatever the item says today.
     */
    fun versionOn(day: LocalDate): ItemVersion? = versions.inForce(item.id, day)

    fun answerOn(day: LocalDate): Answer? = answersByDay[day]

    fun measuredOn(day: LocalDate): MeasuredValue? = measuredByDay[day]

    /** Every period this item has a target in force for on [day] — what decides its view. */
    fun targetsOn(day: LocalDate): List<Target> =
        Period.entries.mapNotNull { targetResolver.resolve(item.id, it, day) }

    /** Whether the item has ever carried a target in this period, which is what earns it figures. */
    fun hasTargetIn(period: Period): Boolean = targets.any { it.period == period }

    private fun requireOwned(what: String, owners: List<ItemId>) {
        val stray = owners.firstOrNull { it != item.id }
        require(stray == null) {
            "$what for ${stray?.value} passed to ${item.id.value}'s history; the calculators do not " +
                "filter by item, so this would be counted as if it belonged here"
        }
    }

    private fun requireOneADay(what: String, days: List<LocalDate>) {
        require(days.distinct().size == days.size) {
            "two $what on one day for ${item.id.value}: a day has one answer, and a second would make " +
                "the week's figure disagree with the days drawn beside it"
        }
    }
}
