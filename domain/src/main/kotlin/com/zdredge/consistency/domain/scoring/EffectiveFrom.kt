package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersion
import java.time.LocalDate

/**
 * Picks the record in force on a date: the one with the latest effective-from that is not in the
 * future relative to [on]. Returns null if nothing was in force yet.
 *
 * Shared by target and container-size resolution, which are the same rule (spec constraint 2)
 * applied to different records. Resolution is always **by date, never by "current"** -- that is what
 * stops a later change from reaching backwards and re-scoring closed periods.
 */
internal fun <T> Iterable<T>.effectiveOn(on: LocalDate, effectiveFrom: (T) -> LocalDate): T? =
    filter { !effectiveFrom(it).isAfter(on) }.maxByOrNull(effectiveFrom)

/**
 * The version of [itemId] in force on [on], or null before the item had one.
 *
 * The same effective-from rule as targets and container sizes, applied to the record that says what
 * an item *is* on a date. Public because both the check-in and the detail view need it: an answer
 * given under version 1 must be read under version 1 for ever, whatever the item says today.
 */
fun List<ItemVersion>.inForce(itemId: ItemId, on: LocalDate): ItemVersion? =
    filter { it.itemId == itemId }.effectiveOn(on) { it.effectiveFrom }
