package com.zdredge.consistency.domain.scoring

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
