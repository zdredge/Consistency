package com.zdredge.consistency.domain.dashboard

import com.zdredge.consistency.domain.TEST_ZONE
import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.checkIn
import com.zdredge.consistency.domain.detail.ItemDetails
import com.zdredge.consistency.domain.history
import com.zdredge.consistency.domain.item
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.CheckIn
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.rollUp
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.time.DayResolver
import com.zdredge.consistency.domain.version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * The dashboard, assembled from histories and check-ins.
 *
 * Display names lead with the scoring-case ID where one applies. Cases 9.1-9.4 -- the 80/60 levels
 * -- were replaced by the trend panels in the M10 review; the trend's own boundary cases take their
 * place here under "M10".
 */
@DisplayName("Assembling the dashboard")
class DashboardTest {

    private val weeks = DayResolver(Clock.fixed(Instant.EPOCH, TEST_ZONE))

    /** A Wednesday: the running week is open and the weeks before it have closed. */
    private val today = LocalDate.of(2026, 9, 30)

    private fun day(back: Int): LocalDate = today.minusDays(back.toLong())

    /** Both check-ins answered on every day from [days] ago to yesterday, and today's still pending. */
    private fun checkIns(days: Int, missed: Set<Int> = emptySet()): List<CheckIn> =
        (days - 1 downTo 1).flatMap { back ->
            val state = if (back in missed) CheckInState.MISSED else CheckInState.ANSWERED
            listOf(checkIn(day(back), Slot.MORNING, state), checkIn(day(back), Slot.NIGHT, state))
        } + listOf(checkIn(today, Slot.MORNING, CheckInState.PENDING), checkIn(today, Slot.NIGHT, CheckInState.PENDING))

    /** Water answered with [bottles] on each day back in [days]. */
    private fun water(days: Map<Int, Double>) = history(
        item = item("water"),
        version = version("water", AnswerType.NUMBER, prompt = "How much water did you drink? (Bottles)", unitLabel = "bottles"),
        targets = listOf(target("water", Direction.AT_LEAST, 2.0)),
        answers = days.map { (back, n) -> answer("water", day = day(back), number = n) },
    )

    /** [metNow] of the last 14 days met, [metBefore] of the 14 before; the rest missed. */
    private fun waterTrend(metNow: Int, metBefore: Int, scoredEach: Int = 14): Map<Int, Double> =
        (0 until scoredEach).associate { it to if (it < metNow) 2.0 else 1.0 } +
            (14 until 14 + scoredEach).associate { it to if (it - 14 < metBefore) 2.0 else 1.0 }

    // ---------------------------------------------------------------- first run

    @Test
    @DisplayName("9.5 - 13 days of history: every figure is suppressed")
    fun thirteenDays() {
        val dashboard = Dashboard.assemble(listOf(water(mapOf(0 to 2.0))), checkIns(13), today, weeks)
        assertEquals(13, dashboard.daysOfHistory)
        assertNull(dashboard.score)
        assertNull(dashboard.trends)
        assertEquals(day(12).plusDays(13), dashboard.scoreFrom, "the day figures appear")
        assertEquals(24, dashboard.answeredSoFar, "twelve closed days, both check-ins")
        assertEquals(24, dashboard.closedSoFar)
    }

    @Test
    @DisplayName("9.6 - 14 days of history: the figures appear, and the trends still wait")
    fun fourteenDays() {
        val dashboard = Dashboard.assemble(listOf(water(mapOf(0 to 2.0))), checkIns(14), today, weeks)
        assertNotNull(dashboard.score)
        assertNull(dashboard.trends, "a trend needs 28 days")
    }

    @Test
    @DisplayName("9.5a - trends appear at 28 days and not at 27")
    fun trendsAtTwentyEight() {
        val histories = listOf(water(waterTrend(metNow = 5, metBefore = 11)))
        assertNull(Dashboard.assemble(histories, checkIns(27), today, weeks).trends)
        assertNotNull(Dashboard.assemble(histories, checkIns(28), today, weeks).trends)
    }

    @Test
    @DisplayName("11.8 - fewer than 14 days: both goal rings are suppressed with the rest")
    fun ringsSuppressedEarly() {
        assertNull(Dashboard.assemble(listOf(water(mapOf(0 to 2.0))), checkIns(10), today, weeks).score)
    }

    // ---------------------------------------------------------------- response rate

    @Test
    @DisplayName("M10 - a check-in still inside its grace counts neither way")
    fun openCheckInsAreNotCounted() {
        val score = Dashboard.assemble(emptyList(), checkIns(20, missed = setOf(3)), today, weeks).score!!
        // 13 closed days in the window (today is pending): 26 check-ins, two of them missed.
        assertEquals(26, score.responseRate.expected)
        assertEquals(24, score.responseRate.answered)
    }

    @Test
    @DisplayName("9.8 - response rate uses the same 14 days as everything else")
    fun oneWindow() {
        // A miss 20 days ago is outside the window and must not count.
        val score = Dashboard.assemble(emptyList(), checkIns(30, missed = setOf(20)), today, weeks).score!!
        assertEquals(1.0, score.responseRate.rate)
    }

    @Test
    @DisplayName("M10 - the longest run counts days every check-in was answered, over all history")
    fun longestRun() {
        val score = Dashboard.assemble(emptyList(), checkIns(30, missed = setOf(10)), today, weeks).score!!
        assertEquals(19, score.longestRun, "days 29 to 11 back")
    }

    // ---------------------------------------------------------------- rings

    @Test
    @DisplayName("11.1 - the daily ring is every daily goal's instances, summed")
    fun dailyRingSumsTheItems() {
        val water = water(waterTrend(metNow = 5, metBefore = 11))
        val vitamins = history(
            item = item("vitamins"),
            version = version("vitamins", AnswerType.BOOL),
            targets = listOf(target("vitamins", Direction.IS_TRUE)),
            answers = (0..13).map { answer("vitamins", day = day(it), bool = it % 2 == 0) },
        )
        val rings = Dashboard.assemble(listOf(water, vitamins), checkIns(30), today, weeks).score!!.rings

        assertEquals(5 + 7, rings.daily.met)
        assertEquals(9 + 7, rings.daily.missed)
    }

    @Test
    @DisplayName("11.5 - no weekly goal scored: the weekly ring is pending, not 0%")
    fun weeklyRingPending() {
        val rings = Dashboard.assemble(listOf(water(waterTrend(5, 11))), checkIns(30), today, weeks).score!!.rings
        assertNull(rings.weekly.ratio)
        assertEquals(0, rings.weekly.scored)
    }

    @Test
    @DisplayName("11.3/11.4 - the weekly ring counts closed weeks in the window only")
    fun weeklyRingClosedWeeks() {
        val rings = Dashboard.assemble(listOf(workedOut()), checkIns(40), today, weeks).score!!.rings
        // The window runs 17-30 September: the week of the 14th closed, the week of the 21st closed,
        // the week of the 28th is open and not scored.
        assertEquals(2, rings.weekly.scored)
    }

    @Test
    @DisplayName("M10 - observations are in no ring and no panel")
    fun observationsAreLeftOut() {
        val mindset = history(
            item = item("mindset"),
            version = version("mindset", AnswerType.SCALE, classification = Classification.OBSERVATION),
            answers = (0..30).map { answer("mindset", day = day(it), scale = 3) },
        )
        val dashboard = Dashboard.assemble(listOf(mindset), checkIns(30), today, weeks)
        assertEquals(0, dashboard.score!!.rings.daily.scored)
        assertTrue(dashboard.trends!!.isEmpty())
    }

    // ---------------------------------------------------------------- trends

    @Test
    @DisplayName("M10 - down from 11 of 14 to 5 of 14 is slipping, told in the goal's own words")
    fun slipping() {
        val goal = Dashboard.assemble(listOf(water(waterTrend(5, 11))), checkIns(30), today, weeks)
            .trends!!.getValue(Trend.SLIPPING).single()

        assertEquals("How much water did you drink?", goal.question)
        assertEquals("at least 2 bottles a day", goal.goal)
        assertEquals(Tally(5, 14), goal.now)
        assertEquals(Tally(11, 14), goal.before)
        assertEquals("days", goal.unit)
        assertEquals(1.0, goal.missedAverage, "the nine missed days were all one bottle")
    }

    @Test
    @DisplayName("9.1a/9.2a - exactly 10 points down is slipping; 8.5 points down is holding")
    fun theSlippingBoundary() {
        // 10 scored each side (4 silent days), 7 met before and 6 now: exactly 10 points.
        assertEquals(Trend.SLIPPING, trendOf(waterTrend(metNow = 6, metBefore = 7, scoredEach = 10)))
        // 13 scored now, 8 met (61.5%), against 7 of 10 (70%).
        val holding = (0 until 13).associate { it to if (it < 8) 2.0 else 1.0 } +
            (14 until 24).associate { it to if (it - 14 < 7) 2.0 else 1.0 }
        assertEquals(Trend.HOLDING, trendOf(holding))
    }

    @Test
    @DisplayName("9.3a - exactly 10 points up is improving")
    fun theImprovingBoundary() {
        assertEquals(Trend.IMPROVING, trendOf(waterTrend(metNow = 7, metBefore = 6, scoredEach = 10)))
    }

    @Test
    @DisplayName("9.4a - a goal with no earlier fortnight has no trend")
    fun noEarlierFortnight() {
        val trends = Dashboard.assemble(listOf(water((0..13).associateWith { 2.0 })), checkIns(30), today, weeks).trends!!
        assertTrue(trends.isEmpty())
    }

    @Test
    @DisplayName("M10 - coffee is placed by its daily hit rate, not its weekly one")
    fun coffeeGoesByTheDay() {
        val coffee = history(
            item = item("coffee"),
            version = version("coffee", AnswerType.NUMBER),
            targets = listOf(
                target("coffee", Direction.AT_MOST, 2.0),
                target("coffee", Direction.AT_MOST, 14.0, period = Period.WEEK),
            ),
            rollUp = rollUp("coffee", RollUpAggregation.SUM),
            // Every day under the daily cap, better now than before; the weekly figures never move.
            answers = (0..27).map { answer("coffee", day = day(it), number = if (it < 14) 1.0 else if (it % 2 == 0) 3.0 else 1.0) },
        )
        val goal = Dashboard.assemble(listOf(coffee), checkIns(30), today, weeks).trends!!.values.flatten().single()
        assertEquals(Period.DAY, goal.period)
        assertEquals(Trend.IMPROVING, goal.trend)
        assertEquals("no more than 2 a day", goal.goal)
    }

    @Test
    @DisplayName("9.7 - a weekly goal compares closed weeks, and shows the open week as progress")
    fun aWeeklyGoal() {
        val goal = Dashboard.assemble(listOf(workedOut()), checkIns(40), today, weeks).trends!!.values.flatten().single()
        assertEquals(Period.WEEK, goal.period)
        assertEquals("weeks", goal.unit)
        assertEquals(2, goal.now.scored)
        assertEquals(2, goal.before.scored)
        assertEquals("yes on at least 3 days a week", goal.goal)
        val open = goal.openWeek!!
        assertEquals(1.0, open.observed, "Monday's workout, the only one so far this week")
        assertEquals(3, open.elapsedDays)
    }

    @Test
    @DisplayName("M10 - the biggest drop leads Slipping")
    fun slippingOrder() {
        val small = water(waterTrend(metNow = 8, metBefore = 11))
        val big = history(
            item = item("meals"),
            version = version("meals", AnswerType.NUMBER),
            targets = listOf(target("meals", Direction.AT_LEAST, 3.0)),
            answers = (0..27).map { answer("meals", day = day(it), number = if (it < 14) (if (it < 2) 3.0 else 2.0) else 3.0) },
        )
        val slipping = Dashboard.assemble(listOf(small, big), checkIns(30), today, weeks).trends!!.getValue(Trend.SLIPPING)
        assertEquals(listOf("meals", "water"), slipping.map { it.itemId.value })
    }

    @Test
    @DisplayName("M10 - the dashboard and the item screen read one goal the same way")
    fun sameAsTheItemScreen() {
        val history = water(waterTrend(5, 11))
        val viaDashboard = Dashboard.assemble(listOf(history), checkIns(30), today, weeks).trends!!.values.flatten().single()
        val viaItem = Dashboard.trendOf(history, ItemDetails.assemble(history, today, weeks))
        assertEquals(viaDashboard, viaItem)
        assertEquals(
            ItemDetails.assemble(history, today, weeks).figures.daily!!.summary.met,
            viaDashboard.now.met,
        )
    }

    private fun trendOf(days: Map<Int, Double>): Trend =
        Dashboard.assemble(listOf(water(days)), checkIns(30), today, weeks).trends!!.values.flatten().single().trend

    /** Worked out: three days a week for weeks, then one a week lately, and Monday of this week. */
    private fun workedOut() = history(
        item = item("worked_out"),
        version = version("worked_out", AnswerType.BOOL),
        targets = listOf(target("worked_out", Direction.AT_LEAST, 3.0, period = Period.WEEK)),
        rollUp = rollUp("worked_out", RollUpAggregation.COUNT_OF_YES),
        answers = (3..40).map { back ->
            val d = day(back)
            val recent = back < 16
            val yes = if (recent) d.dayOfWeek.value == 1 else d.dayOfWeek.value in setOf(1, 3, 5)
            answer("worked_out", day = d, bool = yes)
        } + answer("worked_out", day = weeks.weekStart(today), bool = true),
    )
}
