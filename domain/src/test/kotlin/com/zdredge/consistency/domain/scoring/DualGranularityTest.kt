package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.TEST_ZONE
import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.time.DayResolver
import com.zdredge.consistency.domain.weekOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * docs/scoring-cases.md section 7. Spec 3.4: targets key on (item, period), so one item may hold a
 * daily and a weekly target at once, and the two are scored **independently and reported
 * separately** -- 70,000 steps across three big rides is a different week from 10,000 every day, and
 * collapsing them hides which one happened.
 */
@DisplayName("Dual-granularity targets - scoring-cases section 7")
class DualGranularityTest {

    private val resolver = DayResolver(Clock.fixed(Instant.EPOCH, TEST_ZONE))
    private val monday = LocalDate.of(2026, 8, 24)
    private val week = weekOf(monday, resolver)
    private val steps = ItemId("steps")

    private val targets = TargetResolver(
        listOf(
            target("steps", Direction.AT_LEAST, value = 10_000.0, period = Period.DAY),
            target("steps", Direction.AT_LEAST, value = 70_000.0, period = Period.WEEK),
        )
    )

    private fun dailyOutcomes(perDay: List<Double>) = perDay.mapIndexed { i, n ->
        GoalScorer.score(targets.resolve(steps, Period.DAY, week[i])!!, answer("steps", day = week[i], number = n))
    }

    private fun weeklyOutcome(perDay: List<Double>): GoalOutcome {
        val answers = perDay.mapIndexed { i, n -> answer("steps", day = week[i], number = n) }
        val rollUp = RollUpCalculator.weekly(answers, week, RollUpAggregation.SUM)
        return RollUpCalculator.score(rollUp, targets.resolve(steps, Period.WEEK, monday)!!).outcome
    }

    @Test
    @DisplayName("7.1 - seven days of 8,000 misses daily seven times and misses the week too")
    fun consistentlyShortMissesBoth() {
        val perDay = List(7) { 8_000.0 } // 56,000 total
        assertEquals(7, dailyOutcomes(perDay).count { it.outcome == GoalOutcome.MISSED })
        assertEquals(GoalOutcome.MISSED, weeklyOutcome(perDay))
    }

    @Test
    @DisplayName("7.2 - three days of 25,000 and four of zero: daily met x3, missed x4, weekly MET")
    fun theCaseThatJustifiesTheRule() {
        // A single blended figure would hide which kind of week this was. Both are reported.
        val perDay = listOf(25_000.0, 25_000.0, 25_000.0, 0.0, 0.0, 0.0, 0.0) // 75,000 total
        val daily = dailyOutcomes(perDay)

        assertEquals(3, daily.count { it.outcome == GoalOutcome.MET })
        assertEquals(4, daily.count { it.outcome == GoalOutcome.MISSED })
        assertEquals(GoalOutcome.MET, weeklyOutcome(perDay), "the week hit its total")
    }

    @Test
    @DisplayName("7.3 - coffee with no daily target: a week summing to 13 MEETS at-most 14")
    fun weeklyOnlyTargetsProduceNoDailyMisses() {
        val coffeeTargets = TargetResolver(
            listOf(target("coffee", Direction.AT_MOST, value = 14.0, period = Period.WEEK))
        )
        assertNull(
            coffeeTargets.resolve(ItemId("coffee"), Period.DAY, monday),
            "no daily target exists, so no daily instance can be missed",
        )

        val answers = listOf(2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 1.0)
            .mapIndexed { i, n -> answer("coffee", day = week[i], number = n) } // 13
        val rollUp = RollUpCalculator.weekly(answers, week, RollUpAggregation.SUM)
        assertEquals(13.0, rollUp.value)
        assertEquals(
            GoalOutcome.MET,
            RollUpCalculator.score(rollUp, coffeeTargets.resolve(ItemId("coffee"), Period.WEEK, monday)!!).outcome,
        )
    }

    @Test
    @DisplayName("7.4 - the same week summing to 16 MISSES; one heavy day does not fail on its own")
    fun onlyTheWeeksTotalDecides() {
        // This is the "soft via granularity, not partial credit" behaviour: a 5-coffee day inside an
        // otherwise low week fails nothing by itself. The week's sum decides, and it is still binary.
        val coffeeTarget = target("coffee", Direction.AT_MOST, value = 14.0, period = Period.WEEK)
        val answers = listOf(5.0, 2.0, 2.0, 2.0, 2.0, 2.0, 1.0)
            .mapIndexed { i, n -> answer("coffee", day = week[i], number = n) } // 16
        val rollUp = RollUpCalculator.weekly(answers, week, RollUpAggregation.SUM)

        assertEquals(16.0, rollUp.value)
        assertEquals(GoalOutcome.MISSED, RollUpCalculator.score(rollUp, coffeeTarget).outcome)
    }

    @Test
    @DisplayName("the daily and weekly figures are never collapsed into one")
    fun bothAreReportedSeparately() {
        val perDay = listOf(25_000.0, 25_000.0, 25_000.0, 0.0, 0.0, 0.0, 0.0)
        val dailySummary = ItemSummary.of(dailyOutcomes(perDay))

        // Daily says "you were inconsistent"; weekly says "you hit the total". Both are true, and
        // the pair is the information -- constraint 8.
        assertEquals(3.0 / 7.0, dailySummary.hitRate)
        assertEquals(GoalOutcome.MET, weeklyOutcome(perDay))
    }
}
