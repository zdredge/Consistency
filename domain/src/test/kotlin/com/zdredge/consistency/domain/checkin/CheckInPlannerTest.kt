package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Which check-ins should exist, which is the denominator of the primary metric.
 *
 * A day with no rows is a day that silently never counted, so skipping days would read as
 * improvement rather than as the failure it is. That makes the range case below more than a
 * convenience: it is the reason a week away from the app still costs a week of response rate.
 */
@DisplayName("Check-in planning - spec 1, architecture 5")
class CheckInPlannerTest {

    private val zone = ZoneId.of("America/New_York")
    private val planner = CheckInPlanner(
        DayResolver(Clock.fixed(Instant.parse("2026-08-25T18:00:00Z"), zone)),
    )

    private val tuesday = LocalDate.of(2026, 8, 25)
    private val sunday = LocalDate.of(2026, 8, 30)

    @Test
    @DisplayName("a day expects exactly two check-ins, morning before night")
    fun aDayExpectsTwoCheckIns() {
        assertEquals(listOf(Slot.MORNING, Slot.NIGHT), planner.plan(tuesday).map { it.slot })
    }

    @Test
    @DisplayName("check-ins are scheduled at the configured wall-clock times")
    fun checkInsLandOnTheirConfiguredTimes() {
        val planned = planner.plan(tuesday).associateBy { it.slot }

        assertEquals(instant("2026-08-25T08:00"), planned.getValue(Slot.MORNING).scheduledAt)
        assertEquals(instant("2026-08-25T21:00"), planned.getValue(Slot.NIGHT).scheduledAt)
    }

    /**
     * Sunday carries the weekly questions *appended to that night's check-in* (spec §1), not as a
     * third row. A third would inflate Sunday's denominator for no behavioural reason and make the
     * day structurally harder to score well on.
     */
    @Test
    @DisplayName("Sunday expects the same two check-ins; weekly rides along with the night one")
    fun sundayDoesNotGetAThirdCheckIn() {
        assertEquals(listOf(Slot.MORNING, Slot.NIGHT), planner.plan(sunday).map { it.slot })
        assertTrue(
            planner.plan(sunday).none { it.slot == Slot.WEEKLY },
            "WEEKLY is an item's slot, never a check-in's",
        )
    }

    /**
     * A check-in time before 04:00 belongs to the *next* calendar date, because that is when it next
     * occurs inside this day. Naive `day.atTime(time)` would fire it a full day early.
     */
    @Test
    @DisplayName("a night time before 04:00 schedules into the next calendar morning")
    fun aVeryLateNightTimeSchedulesAfterMidnight() {
        val planned = planner.plan(tuesday, CheckInTimes(night = LocalTime.of(1, 0)))
            .single { it.slot == Slot.NIGHT }

        assertEquals(instant("2026-08-26T01:00"), planned.scheduledAt)
        assertEquals(tuesday, planned.day)
    }

    @Test
    @DisplayName("that same late night check-in is still ordered after the morning one")
    fun theLateNightCheckInStillComesSecond() {
        val planned = planner.plan(tuesday, CheckInTimes(night = LocalTime.of(1, 0)))
        assertEquals(listOf(Slot.MORNING, Slot.NIGHT), planned.map { it.slot })
    }

    /**
     * The days the user never opened the app are exactly the ones that must still count against
     * them, so the range is inclusive at both ends and skips nothing in between.
     */
    @Test
    @DisplayName("a range covers every day inclusive, two check-ins each")
    fun aRangeCoversEveryDayInclusive() {
        val planned = planner.planRange(tuesday, tuesday.plusDays(3))

        assertEquals(8, planned.size)
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 26),
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 28),
            ),
            planned.map { it.day }.distinct(),
        )
    }

    @Test
    @DisplayName("a single-day range still produces that day's two check-ins")
    fun aSingleDayRangeProducesTwo() {
        assertEquals(2, planner.planRange(tuesday, tuesday).size)
    }

    @Test
    @DisplayName("a backwards range produces nothing rather than throwing")
    fun aBackwardsRangeIsEmpty() {
        // Reachable if the device clock moves backwards, which it does across a timezone change or
        // a manual correction. Generating nothing is right; throwing would take the app down on open.
        assertTrue(planner.planRange(tuesday, tuesday.minusDays(1)).isEmpty())
    }

    @Test
    @DisplayName("a range crossing a month boundary does not skip a day")
    fun aRangeCrossingAMonthBoundaryIsContinuous() {
        val planned = planner.planRange(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 2))

        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
            ),
            planned.map { it.day }.distinct(),
        )
    }

    private fun instant(local: String): Instant =
        LocalDateTime.parse(local).atZone(zone).toInstant()
}
