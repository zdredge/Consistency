package com.zdredge.consistency.fixture

import com.zdredge.consistency.domain.checkin.AnswerDay
import com.zdredge.consistency.domain.checkin.CaptureResolver
import com.zdredge.consistency.domain.checkin.CheckInPlanner
import com.zdredge.consistency.domain.checkin.CheckInTimes
import com.zdredge.consistency.domain.checkin.Grace
import com.zdredge.consistency.domain.checkin.RolloverPlanner
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.scoring.ItemLifecycle
import com.zdredge.consistency.domain.scoring.inForce
import com.zdredge.consistency.domain.time.DayResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The baseline history, checked against the app's own rules.
 *
 * Every assertion asks a domain rule rather than restating it. The failure this guards against is a
 * fixture that looks plausible on screen but holds a state the app can never produce -- an answer
 * dated to a version not yet in force, a pending check-in a week old -- which would send a
 * verification session chasing a bug that exists only in the fixture.
 */
class HistoryGeneratorTest {

    private val zone = ZoneId.of("America/New_York")

    /** A Sunday afternoon: today's morning check-in is due, tonight's (with the weekly items) is not. */
    private val sundayAfternoon = clockAt("2026-09-13T16:00")

    /** Before 04:00, which still belongs to the day before -- the boundary every date rule turns on. */
    private val smallHours = clockAt("2026-09-14T02:30")

    private val clocks = listOf(sundayAfternoon, smallHours)

    @Test
    fun everyExpectedCheckInExistsExactlyOnce() = forEachHistory { clock, dataset ->
        val resolver = DayResolver(clock)
        val installDay = dataset.items.minOf { it.createdOn }
        val expected = CheckInPlanner(resolver)
            .planRange(installDay, resolver.today(), CheckInTimes(), dataset.items, dataset.versions)
            .map { it.day to it.slot }

        assertEquals(expected, dataset.checkIns.map { it.day to it.slot })
    }

    @Test
    fun nothingIsAnsweredBeforeItWasDueOrAfterNow() = forEachHistory { clock, dataset ->
        val now = clock.instant()
        dataset.checkIns.forEach { checkIn ->
            if (checkIn.scheduledAt.isAfter(now)) {
                assertEquals("not yet due: $checkIn", CheckInState.PENDING, checkIn.state)
            }
            checkIn.answeredAt?.let {
                assertFalse("answered before due: $checkIn", it.isBefore(checkIn.scheduledAt))
                assertFalse("answered in the future: $checkIn", it.isAfter(now))
            }
        }
    }

    /** The state the rollover exists to prevent. A fixture holding one would verify a broken app. */
    @Test
    fun noCheckInIsLeftPendingPastItsGrace() = forEachHistory { clock, dataset ->
        val today = DayResolver(clock).today()
        dataset.checkIns.filter { it.state == CheckInState.PENDING }.forEach {
            assertFalse("pending past grace: $it", Grace.isPastGrace(it.day, today))
        }
        dataset.checkIns.filter { it.state == CheckInState.MISSED }.forEach {
            assertTrue("missed inside grace: $it", Grace.isPastGrace(it.day, today))
        }
    }

    @Test
    fun everyAnswerComesThroughAnAnsweredCheckInOnTheRightDay() = forEachHistory { _, dataset ->
        val answered = dataset.checkIns.filter { it.state == CheckInState.ANSWERED }.associateBy { it.day to it.slot }
        dataset.answers.forEach { given ->
            val checkIn = answered[given.checkInDay to given.slot]
            assertTrue("no answered check-in for $given", checkIn != null)
            assertEquals(AnswerDay.forCheckIn(given.checkInDay, given.slot), given.answer.day)
            assertEquals(checkIn!!.answeredAt, given.answer.submittedAt)
        }
    }

    @Test
    fun everyAnswerIsToTheVersionInForceForAnItemActiveThatDay() = forEachHistory { _, dataset ->
        val items = dataset.items.associateBy { it.id }
        dataset.answers.map { it.answer }.forEach { answer ->
            assertTrue("inactive item: $answer", ItemLifecycle.isActiveOn(items.getValue(answer.itemId), answer.day))
            assertEquals(
                "wrong version: $answer",
                dataset.versions.inForce(answer.itemId, answer.day)?.id,
                answer.itemVersionId,
            )
        }
    }

    /** Asked of `CaptureResolver` at the moment each answer was given, as the check-in screen does. */
    @Test
    fun everyCaptureIsTheOneTheAppWouldHaveRecorded() = forEachHistory { clock, dataset ->
        dataset.answers.forEach { given ->
            val atTheTime = DayResolver(Clock.fixed(given.answer.submittedAt, clock.zone))
            assertEquals(
                "capture for $given",
                CaptureResolver(atTheTime).forEntry(given.checkInDay),
                given.answer.capture,
            )
        }
    }

    /** Not a tautology over the rule above: a history of only in-window answers would pass it too. */
    @Test
    fun theSixMonthHistoryHoldsBackfillsAndMisses() {
        val dataset = Scenarios.SixMonths.generate(sundayAfternoon)
        assertTrue(dataset.answers.any { it.answer.capture == Capture.BACKFILLED })
        assertTrue(dataset.checkIns.any { it.state == CheckInState.MISSED })
    }

    @Test
    fun noStepValueIsLeftProvisionalPastItsWindow() = forEachHistory { clock, dataset ->
        val resolver = DayResolver(clock)
        val stillToFreeze = RolloverPlanner.plan(resolver.today(), emptyList(), dataset.measuredValues, resolver.now())
        assertTrue("left provisional: ${stillToFreeze.valuesToFreeze}", stillToFreeze.valuesToFreeze.isEmpty())
        assertTrue(dataset.measuredValues.any { it.state == MeasuredState.FROZEN })
    }

    @Test
    fun historiesSpanExactlyTheirDays() {
        clocks.forEach { clock ->
            val today = DayResolver(clock).today()
            mapOf(Scenarios.SixMonths to 183L, Scenarios.Day13 to 13L, Scenarios.Day14 to 14L)
                .forEach { (scenario, days) ->
                    val dataset = scenario.generate(clock)
                    val checkInDays = dataset.checkIns.map { it.day }.toSet()
                    assertEquals("${scenario.id} install day", today.minusDays(days - 1), dataset.items.minOf { it.createdOn })
                    assertEquals("${scenario.id} first check-in", today.minusDays(days - 1), checkInDays.min())
                    assertEquals("${scenario.id} last check-in", today, checkInDays.max())
                    assertEquals("${scenario.id} day count", days, checkInDays.size.toLong())
                }
        }
    }

    @Test
    fun theSameClockGivesTheSameHistory() {
        Scenarios.all.forEach {
            assertEquals(it.id, it.generate(sundayAfternoon), it.generate(sundayAfternoon))
        }
    }

    @Test
    fun scenarioIdsAreUnique() {
        assertEquals(Scenarios.all.size, Scenarios.all.map { it.id }.toSet().size)
    }

    /**
     * Every scenario, plus one history where every check-in is answered the next day.
     *
     * The extra one is there because the scenarios backfill rarely, and today's check-ins -- the ones
     * a next-day answer would put in the future -- are two in hundreds. The rules must hold on the
     * paths the seeds happen not to take.
     */
    private fun forEachHistory(check: (Clock, FixtureDataset) -> Unit) {
        clocks.forEach { clock ->
            Scenarios.all.forEach { check(clock, it.generate(clock)) }
            check(clock, HistoryGenerator(clock, seed = 1, missRate = 0.0, backfillRate = 1.0).generate(5))
        }
    }

    private fun clockAt(local: String): Clock =
        Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)
}
