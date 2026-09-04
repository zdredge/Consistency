package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.TEST_ZONE
import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.answeredDay
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.time.DayResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * docs/scoring-cases.md section 10, spec constraint 17.
 *
 * A no-opportunity answer is an **active answer** that the period did not allow the behaviour. It is
 * excluded from goal completion, does not break the item's run, and costs nothing -- unlike silence,
 * which is also excluded but separately costs the check-in.
 */
@DisplayName("No-opportunity answers - scoring-cases section 10")
class NoOpportunityTest {

    private val noOpp = setOf(OptionId("no_opportunity"))
    private val tookTime = target("took_time", Direction.MUST_INCLUDE, option = "yes")
    private val day1 = LocalDate.of(2026, 8, 24)
    private val resolver = DayResolver(Clock.fixed(Instant.EPOCH, TEST_ZONE))

    private fun score(vararg selections: String) =
        GoalScorer.score(tookTime, answer("took_time", selections = selections.toSet()), noOpp)

    @Test
    @DisplayName("10.1 - took-time answered yes is MET")
    fun yesIsMet() = assertEquals(GoalOutcome.MET, score("yes").outcome)

    @Test
    @DisplayName("10.2 - took-time answered no is MISSED")
    fun noIsMissed() = assertEquals(GoalOutcome.MISSED, score("no").outcome)

    @Test
    @DisplayName("10.3 - took-time answered no-opportunity is EXCLUDED, not met and not missed")
    fun noOpportunityIsExcluded() {
        val result = score("no_opportunity")
        assertEquals(GoalOutcome.EXCLUDED, result.outcome)
        assertEquals(ExclusionReason.NO_OPPORTUNITY, result.exclusionReason)
    }

    @Test
    @DisplayName("10.4 - the check-in stays ANSWERED, so response rate and the run are untouched")
    fun costsNothing() {
        val rate = ResponseRate.of(answeredDay(day1), resolver)
        assertEquals(1.0, rate.rate, "answering no-opportunity is still answering")
        assertEquals(1, RunCalculator.globalRun(answeredDay(day1), upTo = day1).current)
    }

    @Test
    @DisplayName("10.5 - 14 straight no-opportunity days rank in no panel and leave the run intact")
    fun aFortnightOfNoOpportunity() {
        val fortnight = List(14) { score("no_opportunity") }
        val summary = ItemSummary.of(fortnight)

        assertNull(summary.hitRate, "no hits and no misses to rank: it belongs in no panel")
        assertEquals(14, summary.excluded)
        // Constraint 17: usage must stay visible so leaning on it is legible rather than hidden.
        assertEquals(14, summary.noOpportunityCount)

        val outcomes = (0..13).associate { day1.plusDays(it.toLong()) to GoalOutcome.EXCLUDED }
        assertEquals(0, RunCalculator.itemRun(outcomes, upTo = day1.plusDays(13)).current)
        assertEquals(0, RunCalculator.itemRun(outcomes, upTo = day1.plusDays(13)).longest)
    }

    @Test
    @DisplayName("10.6 - a weekly goal answered no-opportunity excludes that week, not misses it")
    fun weeklyNoOpportunityExcludesTheWeek() {
        val invited = target("invited", Direction.MUST_INCLUDE, option = "yes", period = Period.WEEK)
        val result = GoalScorer.score(
            invited,
            answer("invited", selections = setOf("no_opportunity")),
            noOpp,
        )
        assertEquals(GoalOutcome.EXCLUDED, result.outcome)
        assertEquals(ExclusionReason.NO_OPPORTUNITY, result.exclusionReason)
    }

    @Test
    @DisplayName("10.7 - exclusion happens BEFORE direction: the comparator never sees it")
    fun exclusionPrecedesDirection() {
        // Proven by choosing a target the answer would otherwise SATISFY. If the direction ran
        // first this would be MET; it must be EXCLUDED regardless of what the target says.
        val wouldBeMet = target("took_time", Direction.MUST_INCLUDE, option = "no_opportunity")
        val result = GoalScorer.score(
            wouldBeMet,
            answer("took_time", selections = setOf("no_opportunity")),
            noOpp,
        )
        assertEquals(GoalOutcome.EXCLUDED, result.outcome)
        assertEquals(ExclusionReason.NO_OPPORTUNITY, result.exclusionReason)

        // And the mirror: a target it would FAIL is likewise never evaluated.
        val wouldBeMissed = target("took_time", Direction.MUST_NOT_INCLUDE, option = "no_opportunity")
        assertEquals(
            GoalOutcome.EXCLUDED,
            GoalScorer.score(wouldBeMissed, answer("took_time", selections = setOf("no_opportunity")), noOpp).outcome,
        )
    }

    @Test
    @DisplayName("an item with no no-opportunity option configured behaves entirely normally")
    fun withoutAConfiguredOptionNothingChanges() {
        val result = GoalScorer.score(tookTime, answer("took_time", selections = setOf("yes")), emptySet())
        assertEquals(GoalOutcome.MET, result.outcome)
    }
}
