package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **The test the rulebook hinges on.** docs/scoring-cases.md instructs that 10.3 be asserted against
 * 1.13 and A2.1 in one place, so the asymmetry is recorded as deliberate and nobody later "fixes" it
 * into consistency.
 *
 * Three ways of having no positive answer, three different treatments:
 *
 * | case | situation | outcome | what it costs |
 * |------|-----------|---------|----------------|
 * | 1.13 | silence -- no answer at all | EXCLUDED | the check-in, so response rate and the run |
 * | A2.1 | unresolved "not yet"       | **MISSED** | goal completion |
 * | 10.3 | no-opportunity             | EXCLUDED | nothing |
 *
 * Collapsing any pair loses real information. Treating silence as success inflates goal completion
 * on exactly the days the user skipped (constraint 11). Treating a deferral as silence lets an
 * abandoned "not yet" vanish. Treating no-opportunity as a miss punishes a day that genuinely did
 * not allow the behaviour (constraint 17).
 */
@DisplayName("Silence vs pending vs no-opportunity - 1.13 / A2.1 / 10.3 side by side")
class ThreeWayDistinctionTest {

    private val noOpp = setOf(OptionId("no_opportunity"))
    private val goal = target("took_time", Direction.MUST_INCLUDE, option = "yes")

    private val silence = GoalScorer.score(goal, answer = null, noOpp)
    private val unresolvedPending =
        GoalScorer.score(goal, answer("took_time", capture = Capture.PENDING), noOpp)
    private val noOpportunity =
        GoalScorer.score(goal, answer("took_time", selections = setOf("no_opportunity")), noOpp)

    @Test
    @DisplayName("1.13 - silence is EXCLUDED, for the reason NO_ANSWER")
    fun silenceIsExcluded() {
        assertEquals(GoalOutcome.EXCLUDED, silence.outcome)
        assertEquals(ExclusionReason.NO_ANSWER, silence.exclusionReason)
    }

    @Test
    @DisplayName("A2.1 - an unresolved pending is MISSED, the rulebook's only absent-value miss")
    fun pendingIsMissed() {
        assertEquals(GoalOutcome.MISSED, unresolvedPending.outcome)
    }

    @Test
    @DisplayName("10.3 - no-opportunity is EXCLUDED, for the reason NO_OPPORTUNITY")
    fun noOpportunityIsExcluded() {
        assertEquals(GoalOutcome.EXCLUDED, noOpportunity.outcome)
        assertEquals(ExclusionReason.NO_OPPORTUNITY, noOpportunity.exclusionReason)
    }

    @Test
    @DisplayName("the three are mutually distinguishable and must stay so")
    fun theThreeAreNotInterchangeable() {
        // Pending differs from both exclusions by outcome...
        assertNotEquals(silence.outcome, unresolvedPending.outcome)
        assertNotEquals(noOpportunity.outcome, unresolvedPending.outcome)

        // ...and the two exclusions, which share an outcome, differ by reason. This is precisely
        // why EXCLUDED alone is not enough: silence costs a check-in, no-opportunity costs nothing.
        assertEquals(silence.outcome, noOpportunity.outcome)
        assertNotEquals(silence.exclusionReason, noOpportunity.exclusionReason)
    }

    @Test
    @DisplayName("only the pending case touches goal completion; both exclusions leave it alone")
    fun onlyPendingReachesTheDenominator() {
        val summary = ItemSummary.of(listOf(silence, unresolvedPending, noOpportunity))
        assertEquals(0, summary.met)
        assertEquals(1, summary.missed, "the deferral, and only the deferral")
        assertEquals(2, summary.excluded)
        assertEquals(0.0, summary.hitRate, "0 met of 1 scored instance")
        assertEquals(1, summary.noOpportunityCount, "and the neutral one stays separately visible")
    }
}
