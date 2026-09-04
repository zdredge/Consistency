package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.ContainerSize
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Target
import java.time.LocalDate

/**
 * Resolves which target was in force for an item, in a period, on a date.
 *
 * Targets key on (item, period), so one item can hold a daily and a weekly target at once and they
 * are scored independently, never collapsed (spec 3.4). A weekly lookup therefore never returns a
 * daily target, even for the same item.
 */
class TargetResolver(private val targets: List<Target>) {

    fun resolve(itemId: ItemId, period: Period, on: LocalDate): Target? =
        targets
            .filter { it.itemId == itemId && it.period == period }
            .effectiveOn(on) { it.effectiveFrom }

    /** Every period for which this item has a target in force on [on]. */
    fun periodsWithTargets(itemId: ItemId, on: LocalDate): Set<Period> =
        Period.entries.filter { resolve(itemId, it, on) != null }.toSet()
}

/**
 * Resolves counted-container sizes. Entering "2" records both the count and the absolute amount
 * (spec 3.3); the size is stored per period so changing bottles later does not rewrite what past
 * answers meant.
 */
class ContainerSizeResolver(private val sizes: List<ContainerSize>) {

    fun resolve(itemId: ItemId, on: LocalDate): ContainerSize? =
        sizes.filter { it.itemId == itemId }.effectiveOn(on) { it.effectiveFrom }

    /**
     * The absolute amount a count represents on a given day, or null if the item has no container
     * size in force (in which case the count is the amount).
     */
    fun absoluteAmount(itemId: ItemId, count: Double, on: LocalDate): Double? =
        resolve(itemId, on)?.let { count * it.size }
}
