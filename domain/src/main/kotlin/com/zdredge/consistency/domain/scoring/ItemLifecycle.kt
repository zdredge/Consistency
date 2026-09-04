package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.Item
import java.time.LocalDate

/**
 * Whether an item was live at a given moment. Spec 3.4: **scoring covers only items active in the
 * period being scored.**
 *
 * Items are versioned and retired, never deleted (constraint 6), so history keeps referring to items
 * that are no longer asked. A retired item stays visible for the periods it was active in and is
 * absent from those it was not -- which means "not active" must be an EXCLUSION, never a miss. An
 * item that did not exist yet cannot have been skipped.
 */
object ItemLifecycle {

    /** Both endpoints are inclusive: the creation day and the retirement day both count. */
    fun isActiveOn(item: Item, date: LocalDate): Boolean =
        !date.isBefore(item.createdOn) && (item.retiredOn?.let { !date.isAfter(it) } ?: true)

    /**
     * True when the item was active for **any part** of the period. A goal retired mid-week was
     * still a real goal for the days before it went, so the week is not simply erased.
     */
    fun isActiveInPeriod(item: Item, start: LocalDate, endInclusive: LocalDate): Boolean =
        !item.createdOn.isAfter(endInclusive) && (item.retiredOn?.let { !it.isBefore(start) } ?: true)
}
