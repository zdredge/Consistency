package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** When the two daily check-ins are due. Spec §1 gives 21:00 as the night default. */
data class CheckInTimes(
    val morning: LocalTime = LocalTime.of(8, 0),
    val night: LocalTime = LocalTime.of(21, 0),
)

/** A check-in that should exist. Not yet a row; `:data` turns these into `checkins`. */
data class PlannedCheckIn(
    val day: LocalDate,
    val slot: Slot,
    val scheduledAt: Instant,
)

/**
 * Which check-ins should exist for a day.
 *
 * **This is the denominator of the primary metric.** A row must exist for every check-in that was
 * *expected*, or response rate is not merely wrong but unmeasurable — a day with no row is a day
 * that silently never counted, which would make skipping days look like improvement (architecture
 * §5). So this runs for every day since the app was last opened, not only for today.
 *
 * It is pure and returns plain data on purpose. M4 calls it on app open; **M5 wraps this same
 * function in the rollover worker** and adds the rest of that job — marking check-ins missed,
 * converting unresolved deferrals, freezing step values. Two callers, one rule, no second
 * implementation to drift.
 *
 * **Two check-ins a day, always.** The weekly questions are *appended to Sunday night's check-in*
 * rather than forming a third (spec §1), so `Slot.WEEKLY` is an item's slot and never a check-in's.
 * A third row on Sundays would inflate that day's denominator for no behavioural reason, making
 * Sunday structurally harder to score well on.
 */
class CheckInPlanner(private val dayResolver: DayResolver) {

    /** The check-ins expected on [day], in the order they occur. */
    fun plan(day: LocalDate, times: CheckInTimes = CheckInTimes()): List<PlannedCheckIn> = listOf(
        PlannedCheckIn(day, Slot.MORNING, dayResolver.instantAt(day, times.morning)),
        PlannedCheckIn(day, Slot.NIGHT, dayResolver.instantAt(day, times.night)),
    ).sortedBy { it.scheduledAt }

    /**
     * The check-ins expected on [day], **excluding any that would ask nothing**.
     *
     * A check-in with no questions was never really expected, and generating one puts an
     * unanswerable row into the response-rate denominator — a miss the user could not have avoided.
     * That is not hypothetical: on install day the morning check-in covers *yesterday*, when no item
     * existed yet, so the very first thing a new user would see is a check-in they cannot answer and
     * a metric already counting against them. Found by installing it and looking.
     *
     * The same rule covers the case where every item has been retired. Nothing to ask, nothing
     * expected, nothing missed.
     */
    fun plan(
        day: LocalDate,
        times: CheckInTimes,
        items: List<Item>,
        versions: List<ItemVersion>,
    ): List<PlannedCheckIn> = plan(day, times).filter { planned ->
        CheckInContent.forCheckIn(day, planned.slot, items, versions).isNotEmpty()
    }

    /** As [planRange], but skipping check-ins that would ask nothing. See [plan]. */
    fun planRange(
        from: LocalDate,
        to: LocalDate,
        times: CheckInTimes,
        items: List<Item>,
        versions: List<ItemVersion>,
    ): List<PlannedCheckIn> {
        if (from.isAfter(to)) return emptyList()
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .flatMap { plan(it, times, items, versions).asSequence() }
            .toList()
    }

    /**
     * Every check-in expected from [from] to [to], both inclusive.
     *
     * The caller passes the last day already covered and today; days in between are the ones the
     * user never opened the app on, and those are exactly the ones that must still count against
     * them.
     */
    fun planRange(
        from: LocalDate,
        to: LocalDate,
        times: CheckInTimes = CheckInTimes(),
    ): List<PlannedCheckIn> {
        if (from.isAfter(to)) return emptyList()
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .flatMap { plan(it, times).asSequence() }
            .toList()
    }
}
