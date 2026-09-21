package com.zdredge.consistency.fixture

import com.zdredge.consistency.domain.checkin.Grace
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.scoring.ContainerSizeResolver
import com.zdredge.consistency.domain.scoring.GoalScorer
import com.zdredge.consistency.domain.scoring.ItemLifecycle
import com.zdredge.consistency.domain.scoring.MeasuredScorer
import com.zdredge.consistency.domain.scoring.TargetResolver
import com.zdredge.consistency.domain.time.DayResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Each scenario holds the state its name promises (build-order M9 exit criterion).
 *
 * The question is put to the calculators the app scores with -- "is this excluded" is asked of
 * `GoalScorer`, "which target applies" of `TargetResolver` -- because a scenario that only *looked*
 * like a retired item, to a test that re-derived retirement by hand, could fail to exercise the app
 * at all.
 */
class ScenarioStatesTest {

    private val zone = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(LocalDateTime.parse("2026-09-13T16:00").atZone(zone).toInstant(), zone)
    private val today = DayResolver(clock).today()

    @Test
    fun capturesHoldEveryCaptureState() {
        val dataset = Scenarios.Captures.generate(clock)
        val captures = dataset.answers.map { it.answer.capture }.toSet()
        assertEquals(Capture.entries.toSet(), captures)
    }

    @Test
    fun capturesResolveADeferralInWindowTheNextMorning() {
        val resolved = Scenarios.Captures.generate(clock).answers.filter { it.carriedOverFrom != null }
        assertTrue("no resolved deferral", resolved.isNotEmpty())
        resolved.forEach {
            assertEquals(Slot.MORNING, it.slot)
            assertEquals(it.carriedOverFrom!!.plusDays(1), it.checkInDay)
            assertEquals("a resolved deferral is in-window (A2.2)", Capture.IN_WINDOW, it.answer.capture)
        }
    }

    /** A2.1: the only absent value that scores as a miss -- once it can no longer be resolved. */
    @Test
    fun capturesLeaveOneDeferralUnresolvedPastGraceAndOneStillOpen() {
        val dataset = Scenarios.Captures.generate(clock)
        val pending = dataset.answers.filter { it.answer.capture == Capture.PENDING }
        val target = TargetResolver(dataset.targets)

        val (closed, open) = pending.partition { Grace.isPastGrace(it.checkInDay.plusDays(1), today) }
        assertTrue("no unresolved deferral past grace", closed.isNotEmpty())
        assertTrue("no deferral still open", open.isNotEmpty())

        closed.forEach {
            val goal = target.resolve(it.answer.itemId, Period.DAY, it.answer.day) ?: return@forEach
            assertEquals(GoalOutcome.MISSED, GoalScorer.score(goal, it.answer).outcome)
        }
        open.forEach {
            val goal = target.resolve(it.answer.itemId, Period.DAY, it.answer.day) ?: return@forEach
            assertEquals(GoalOutcome.EXCLUDED, GoalScorer.score(goal, it.answer, stillResolvable = true).outcome)
        }
    }

    @Test
    fun editedAnswersKeepTheirCaptureAndCarryAnEditStamp() {
        val edited = Scenarios.Edited.generate(clock).answers.filter { it.answer.editedAt != null }
        assertTrue("only ${edited.size} edited answers", edited.size >= 10)
        assertTrue(edited.any { it.slot == Slot.MORNING } && edited.any { it.slot == Slot.NIGHT })
    }

    @Test
    fun theRetiredItemIsAnsweredOnlyWhileActive() {
        val dataset = Scenarios.RetiredReversioned.generate(clock)
        val stretched = dataset.items.single { it.id == Scenarios.STRETCHED }
        val retiredOn = stretched.createdOn.plusDays(Scenarios.RETIRED_ON)
        assertEquals(retiredOn, stretched.retiredOn)

        val days = dataset.answers.filter { it.answer.itemId == Scenarios.STRETCHED }.map { it.answer.day }
        assertTrue("never answered before retiring", days.isNotEmpty())
        assertTrue("answered after retiring", days.all { !it.isAfter(retiredOn) })
        assertFalse(ItemLifecycle.isActiveOn(stretched, retiredOn.plusDays(1)))
    }

    @Test
    fun theRewordedQuestionPinsEachAnswerToItsVersion() {
        val dataset = Scenarios.RetiredReversioned.generate(clock)
        val installDay = dataset.items.minOf { it.createdOn }
        val changedOn = installDay.plusDays(Scenarios.REVERSIONED_ON)
        val meals = dataset.answers.map { it.answer }.filter { it.itemId == Scenarios.MEALS }

        val (before, after) = meals.partition { it.day.isBefore(changedOn) }
        assertTrue(before.isNotEmpty() && after.isNotEmpty())
        assertTrue(before.all { it.itemVersionId == ItemVersionId("meals.v1") })
        assertTrue(after.all { it.itemVersionId == ItemVersionId("meals.v2") })
    }

    @Test
    fun conflictedStepDaysAreExcludedNotMissed() {
        val dataset = Scenarios.MultiOriginSteps.generate(clock)
        val target = TargetResolver(dataset.targets)
        val conflicted = dataset.measuredValues.filter { it.state == MeasuredState.CONFLICTED }

        assertEquals(5, conflicted.size)
        conflicted.forEach {
            assertEquals(2, it.origins.size)
            val result = MeasuredScorer.score(target.resolve(it.itemId, Period.DAY, it.day)!!, it)
            assertEquals(ExclusionReason.SOURCE_CONFLICT, result.exclusionReason)
        }
        assertTrue("yesterday is one of them", conflicted.any { it.day == today.minusDays(1) })
        assertTrue(dataset.measuredValues.any { it.state == MeasuredState.FROZEN })
        assertTrue(dataset.measuredValues.any { it.state == MeasuredState.PROVISIONAL })
    }

    @Test
    fun gappyWeeksHoldASilentWeekAndDaysWithoutSteps() {
        val dataset = Scenarios.GappyWeeks.generate(clock)
        val installDay = dataset.items.minOf { it.createdOn }
        val silentDays = dataset.checkIns.groupBy { it.day }
            .filterValues { day -> day.all { it.state == CheckInState.MISSED } }
            .keys

        Scenarios.GAPPY_SILENT.map { installDay.plusDays(it) }.forEach {
            assertTrue("$it should be silent", it in silentDays)
        }
        val worstWeek = silentDays.groupingBy { DayResolver(clock).weekStart(it) }.eachCount().values.max()
        assertTrue("worst week has only $worstWeek silent days", worstWeek >= 4)

        val stepDays = dataset.measuredValues.map { it.day }.toSet()
        listOf(15L, 16L, 17L, 33L).forEach { assertFalse(installDay.plusDays(it) in stepDays) }
    }

    @Test
    fun anEarlierPeriodKeepsTheOldTargetAndBottleSize() {
        val dataset = Scenarios.EffectiveFrom.generate(clock)
        val changedOn = dataset.items.minOf { it.createdOn }.plusDays(Scenarios.CHANGED_ON)
        val targets = TargetResolver(dataset.targets)
        val sizes = ContainerSizeResolver(dataset.containerSizes)
        val dayBefore = changedOn.minusDays(1)

        assertEquals(2.0, targets.resolve(Scenarios.WATER, Period.DAY, dayBefore)!!.valueNumber!!, 0.0)
        assertEquals(3.0, targets.resolve(Scenarios.WATER, Period.DAY, changedOn)!!.valueNumber!!, 0.0)
        assertEquals(80.0, sizes.absoluteAmount(Scenarios.WATER, 2.0, dayBefore)!!, 0.0)
        assertEquals(64.0, sizes.absoluteAmount(Scenarios.WATER, 2.0, changedOn)!!, 0.0)

        // The same two bottles, scored either side of the change: met before it, missed after.
        val twoBottles = dataset.answers.map { it.answer }.filter { it.itemId == Scenarios.WATER && it.valueNumber == 2.0 }
        val outcome = { day: LocalDate -> twoBottles.first { it.day == day }.let { GoalScorer.score(targets.resolve(Scenarios.WATER, Period.DAY, day)!!, it).outcome } }
        assertEquals(GoalOutcome.MET, outcome(twoBottles.first { it.day.isBefore(changedOn) }.day))
        assertEquals(GoalOutcome.MISSED, outcome(twoBottles.first { !it.day.isBefore(changedOn) }.day))
    }

    @Test
    fun fourSundaysInARowHadNoOpportunity() {
        val dataset = Scenarios.NoOpportunity.generate(clock)
        val noOpportunity = dataset.options.filter { it.isNoOpportunity }.map { it.id }.toSet()
        val target = TargetResolver(dataset.targets)

        val excludedSundays = dataset.answers.map { it.answer }
            .filter { it.itemId == Scenarios.SAW_FRIENDS }
            .filter {
                val goal = target.resolve(it.itemId, Period.WEEK, it.day)!!
                GoalScorer.score(goal, it, noOpportunity).exclusionReason == ExclusionReason.NO_OPPORTUNITY
            }
            .map { it.day }
            .sorted()

        assertTrue(excludedSundays.all { it.dayOfWeek == DayOfWeek.SUNDAY })
        val longestStreak = excludedSundays.zipWithNext().fold(1 to 1) { (best, run), (a, b) ->
            val next = if (b == a.plusWeeks(1)) run + 1 else 1
            maxOf(best, next) to next
        }.first
        // At least: an ordinary Sunday is occasionally "no opportunity" too.
        assertTrue("longest run of no-opportunity Sundays is $longestStreak", longestStreak >= 4)
    }

    @Test
    fun theBaselineHasNoStateItHasNoBusinessHaving() {
        val dataset = Scenarios.SixMonths.generate(clock)
        assertTrue(dataset.answers.none { it.answer.editedAt != null })
        assertTrue(dataset.answers.none { it.answer.capture == Capture.LATE || it.answer.capture == Capture.PENDING })
        assertTrue(dataset.measuredValues.none { it.state == MeasuredState.CONFLICTED })
        assertNull(dataset.items.firstOrNull { it.retiredOn != null })
    }
}
