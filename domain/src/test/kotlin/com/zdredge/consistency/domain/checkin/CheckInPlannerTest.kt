package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.ItemVersionId
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

    // ---- A check-in that would ask nothing was never expected --------------------------------

    /**
     * **The install-day bug, as a test.** The morning check-in covers yesterday, and on the day the
     * library is seeded no item existed yesterday. Generating it would put an unanswerable row into
     * the response-rate denominator, so a brand-new user opens the app already counted against.
     */
    @Test
    @DisplayName("on install day the morning check-in is not expected, because it would ask nothing")
    fun installDayExpectsOnlyTheNightCheckIn() {
        val items = listOf(item("meals", createdOn = tuesday), item("bedtime", createdOn = tuesday))
        val versions = listOf(
            version("meals", Slot.NIGHT, tuesday),
            version("bedtime", Slot.MORNING, tuesday),
        )

        assertEquals(
            listOf(Slot.NIGHT),
            planner.plan(tuesday, CheckInTimes(), items, versions).map { it.slot },
        )
    }

    @Test
    @DisplayName("the next day expects both, because yesterday now has items")
    fun theDayAfterInstallExpectsBoth() {
        val items = listOf(item("meals", createdOn = tuesday), item("bedtime", createdOn = tuesday))
        val versions = listOf(
            version("meals", Slot.NIGHT, tuesday),
            version("bedtime", Slot.MORNING, tuesday),
        )

        assertEquals(
            listOf(Slot.MORNING, Slot.NIGHT),
            planner.plan(tuesday.plusDays(1), CheckInTimes(), items, versions).map { it.slot },
        )
    }

    @Test
    @DisplayName("with every item retired, no check-in is expected at all")
    fun anEmptyLibraryExpectsNothing() {
        // Nothing to ask, nothing expected, nothing missed. The same rule as install day, from the
        // other end of an item's life.
        val items = listOf(
            item("meals", createdOn = LocalDate.of(2026, 8, 1), retiredOn = LocalDate.of(2026, 8, 10)),
        )
        val versions = listOf(version("meals", Slot.NIGHT, LocalDate.of(2026, 8, 1)))

        assertTrue(planner.plan(tuesday, CheckInTimes(), items, versions).isEmpty())
    }

    @Test
    @DisplayName("a range skips the days that would ask nothing and keeps the rest")
    fun aRangeSkipsEmptyCheckIns() {
        val items = listOf(item("meals", createdOn = tuesday))
        val versions = listOf(version("meals", Slot.NIGHT, tuesday))

        val planned = planner.planRange(tuesday, tuesday.plusDays(2), CheckInTimes(), items, versions)

        // Night only, every day: nothing is ever asked in the morning because no morning item exists.
        assertEquals(listOf(Slot.NIGHT, Slot.NIGHT, Slot.NIGHT), planned.map { it.slot })
    }

    private fun item(
        id: String,
        createdOn: LocalDate,
        retiredOn: LocalDate? = null,
    ) = Item(ItemId(id), ItemKind.ASKED, createdOn, retiredOn)

    private fun version(itemId: String, slot: Slot, from: LocalDate) = ItemVersion(
        id = ItemVersionId("$itemId.v1"),
        itemId = ItemId(itemId),
        versionNo = 1,
        prompt = itemId,
        answerType = AnswerType.NUMBER,
        classification = Classification.GOAL,
        slot = slot,
        effectiveFrom = from,
    )

    private fun instant(local: String): Instant =
        LocalDateTime.parse(local).atZone(zone).toInstant()
}
