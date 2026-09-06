package com.zdredge.consistency.domain.time

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The single authority on which day a timestamp belongs to.
 *
 * Spec appendix constraint 1: the day boundary is **04:00, not midnight** — a 01:30 bedtime belongs
 * to the previous day. docs/architecture.md section 5 is explicit that this rule is the kind that
 * gets reimplemented slightly differently in four places, so **nothing else in the codebase may
 * compute this**. All date arithmetic goes through here and the injected [Clock].
 *
 * Weeks start Monday (spec section 3.1) and are always *derived* from a date, never stored
 * (constraint 7).
 *
 * The [Clock] is injected so tests can drive "now" to any instant; production passes
 * `Clock.systemDefaultZone()`. The clock also supplies the zone, so this class never reads the
 * system zone itself.
 */
class DayResolver(private val clock: Clock) {

    /**
     * The day [instant] belongs to. Anything before 04:00 local belongs to the previous calendar
     * day; 04:00 itself starts the new day.
     */
    fun dayFor(instant: Instant): LocalDate {
        val local = ZonedDateTime.ofInstant(instant, clock.zone)
        return if (local.toLocalTime() < DAY_START) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
    }

    /** The day "now" belongs to, per the injected clock. */
    fun today(): LocalDate = dayFor(clock.instant())

    /**
     * The current instant, per the injected clock.
     *
     * Exposed so callers that need a timestamp -- an answer's `submitted_at`, a check-in's
     * `answered_at` -- take it from the same clock everything else dates through, rather than
     * reaching for `Instant.now()` and quietly escaping the test clock.
     */
    fun now(): Instant = clock.instant()

    /** The instant [day] begins — 04:00 local. */
    fun startOfDay(day: LocalDate): Instant =
        day.atTime(DAY_START).atZone(clock.zone).toInstant()

    /**
     * The exclusive end of [day], i.e. the start of the next one. Exclusive rather than
     * "23:59:59.999" so range checks cannot silently drop the final fraction of a second.
     */
    fun endOfDayExclusive(day: LocalDate): Instant = startOfDay(day.plusDays(1))

    /**
     * The instant at which [time] occurs **within** [day], respecting the 04:00 boundary.
     *
     * A wall-clock time at or after 04:00 falls on the same calendar date; anything earlier belongs
     * to the *next* calendar date, because that is when it next occurs inside this day. A night
     * check-in set to 01:00 on Tuesday happens in the small hours of Wednesday morning — and a
     * scheduler that naively used `day.atTime(time)` would fire it a full day early.
     *
     * This lives here rather than in the caller for the reason the whole class exists: the boundary
     * is the kind of rule that gets reimplemented slightly differently in four places
     * (architecture §5).
     */
    fun instantAt(day: LocalDate, time: LocalTime): Instant {
        val date = if (time < DAY_START) day.plusDays(1) else day
        return date.atTime(time).atZone(clock.zone).toInstant()
    }

    /** The Monday of the week containing [day]. A Monday resolves to itself. */
    fun weekStart(day: LocalDate): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The Sunday closing the week containing [day]. */
    fun weekEnd(day: LocalDate): LocalDate = weekStart(day).plusDays(6)

    companion object {
        /** Spec appendix constraint 1. Changing this re-dates every historical record. */
        val DAY_START: LocalTime = LocalTime.of(4, 0)
    }
}
