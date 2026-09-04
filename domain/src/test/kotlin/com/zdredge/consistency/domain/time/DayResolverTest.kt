package com.zdredge.consistency.domain.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The 04:00 day boundary is spec appendix constraint 1; the sleep-day cases come from
 * docs/scoring-cases.md 8.2 and 8.5. Nothing else in the codebase may compute which day a
 * timestamp belongs to (docs/architecture.md section 5), so this is the one place it is proven.
 *
 * Naming convention: conventional camelCase identifiers, with @DisplayName carrying the
 * doc-faithful text. Display names can hold characters a JVM method name cannot -- colons in
 * "04:00" and the periods in scoring-case IDs like "8.5" -- which keeps tests traceable to the
 * documented case they cover.
 *
 * The zone is fixed and deliberately not UTC, so a zone-handling bug cannot hide behind a zero
 * offset. Production uses the system default zone via the injected Clock.
 */
@DisplayName("DayResolver - the 04:00 day boundary and Monday weeks")
class DayResolverTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun instantAt(local: String): Instant =
        LocalDateTime.parse(local).atZone(zone).toInstant()

    /** A resolver parked at an arbitrary instant; most cases supply their own input. */
    private fun resolver() = DayResolver(Clock.fixed(Instant.EPOCH, zone))

    /** A resolver whose "now" is the given local time -- for exercising today(). */
    private fun resolverAt(local: String) = DayResolver(Clock.fixed(instantAt(local), zone))

    @ParameterizedTest(name = "{0} belongs to {1}")
    @CsvSource(
        "2026-08-26T00:00,        2026-08-25", // midnight is NOT the boundary
        "2026-08-26T01:30,        2026-08-25", // scoring-cases 8.2 / 8.5
        "2026-08-26T03:59,        2026-08-25", // just before the boundary
        "2026-08-26T03:59:59.999, 2026-08-25", // the last instant of the previous day
        "2026-08-26T04:00,        2026-08-26", // the boundary itself starts the new day
        "2026-08-26T12:00,        2026-08-26",
        "2026-08-26T23:30,        2026-08-26",
    )
    @DisplayName("dayFor applies the 04:00 boundary, not midnight")
    fun dayForAppliesFourAmBoundary(local: String, expected: String) {
        assertEquals(LocalDate.parse(expected), resolver().dayFor(instantAt(local)))
    }

    @Test
    @DisplayName("8.5 - a 01:30 bedtime on the 26th is dated the 25th")
    fun sleepDayConventionAgreesWithFourAmRule() {
        assertEquals(
            LocalDate.of(2026, 8, 25),
            resolver().dayFor(instantAt("2026-08-26T01:30")),
        )
    }

    @Test
    @DisplayName("startOfDay is 04:00 local time")
    fun startOfDayIsFourAmLocal() {
        assertEquals(instantAt("2026-08-25T04:00"), resolver().startOfDay(LocalDate.of(2026, 8, 25)))
    }

    @Test
    @DisplayName("startOfDay round-trips through dayFor")
    fun startOfDayRoundTripsThroughDayFor() {
        val day = LocalDate.of(2026, 8, 25)
        assertEquals(day, resolver().dayFor(resolver().startOfDay(day)))
    }

    @Test
    @DisplayName("endOfDayExclusive is the next day's start")
    fun endOfDayExclusiveIsNextDayStart() {
        val r = resolver()
        val day = LocalDate.of(2026, 8, 25)
        assertEquals(r.startOfDay(day.plusDays(1)), r.endOfDayExclusive(day))
    }

    @Test
    @DisplayName("the instant before endOfDayExclusive still belongs to the day")
    fun instantBeforeEndOfDayStillBelongsToDay() {
        val r = resolver()
        val day = LocalDate.of(2026, 8, 25)
        assertEquals(day, r.dayFor(r.endOfDayExclusive(day).minusMillis(1)))
    }

    @ParameterizedTest(name = "week containing {0} starts {1}")
    @CsvSource(
        "2026-08-24, 2026-08-24", // a Monday is its own week start
        "2026-08-25, 2026-08-24", // Tuesday
        "2026-08-28, 2026-08-24", // Friday
        "2026-08-30, 2026-08-24", // Sunday still belongs to the preceding Monday's week
        "2026-08-31, 2026-08-31", // the next Monday opens a new week
    )
    @DisplayName("weekStart is the preceding Monday (spec 3.1: weeks start Monday)")
    fun weekStartIsPrecedingMonday(day: String, expected: String) {
        assertEquals(LocalDate.parse(expected), resolver().weekStart(LocalDate.parse(day)))
    }

    @Test
    @DisplayName("weekEnd is the Sunday six days after weekStart")
    fun weekEndIsSundaySixDaysAfterWeekStart() {
        assertEquals(LocalDate.of(2026, 8, 30), resolver().weekEnd(LocalDate.of(2026, 8, 25)))
    }

    @Test
    @DisplayName("today() follows the injected clock - before the 04:00 boundary")
    fun todayFollowsInjectedClockBeforeBoundary() {
        assertEquals(LocalDate.of(2026, 8, 25), resolverAt("2026-08-26T01:30").today())
    }

    @Test
    @DisplayName("today() follows the injected clock - after the 04:00 boundary")
    fun todayFollowsInjectedClockAfterBoundary() {
        assertEquals(LocalDate.of(2026, 8, 26), resolverAt("2026-08-26T04:00").today())
    }
}
