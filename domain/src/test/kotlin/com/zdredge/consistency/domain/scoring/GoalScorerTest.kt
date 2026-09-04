package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * docs/scoring-cases.md section 2. Spec constraint 16: attainment is reported ALONGSIDE the binary
 * outcome, never merged into it and never dropped.
 *
 * Scoring stays binary because partial credit lets someone sit at a comfortable 75% forever without
 * ever hitting a target. But a binary number alone is a bad description of reality -- 1.5 of 2
 * bottles every day for a fortnight is a hit rate of zero, and reporting only that turns perfect
 * consistency into apparent total failure. So both numbers are produced, and neither substitutes
 * for the other.
 */
@DisplayName("Binary scoring with separate attainment - scoring-cases section 2")
class GoalScorerTest {

    private val waterTarget = target("water", Direction.AT_LEAST, value = 2.0)

    @Test
    @DisplayName("2.1 - AT_LEAST 2, answer 1.5 is MISSED and contributes 0, not 0.75")
    fun shortfallIsAMissNotPartialCredit() {
        val result = GoalScorer.score(waterTarget, answer("water", number = 1.5))
        assertEquals(GoalOutcome.MISSED, result.outcome)
    }

    @Test
    @DisplayName("2.2 - the same day reports attainment of 75%, independently of the score")
    fun attainmentIsReportedAlongsideTheMiss() {
        val result = GoalScorer.score(waterTarget, answer("water", number = 1.5))
        assertEquals(0.75, result.attainment)
        assertEquals(GoalOutcome.MISSED, result.outcome, "attainment must not soften the outcome")
    }

    @Test
    @DisplayName("2.3 - 1.5 of 2 on all 14 days: hit rate 0%, average attainment 75%")
    fun consistentNearMissesProduceBothNumbers() {
        // The case that justifies the whole concept. Reporting only hit rate would make a fortnight
        // of perfect consistency read as unqualified failure.
        val fortnight = List(14) { GoalScorer.score(waterTarget, answer("water", number = 1.5)) }
        val summary = ItemSummary.of(fortnight)

        assertEquals(0.0, summary.hitRate)
        assertEquals(0.75, summary.averageAttainment)
    }

    @Test
    @DisplayName("2.4 - AT_LEAST 2, answer 3 is MET and attainment caps at 100%, not 150%")
    fun attainmentIsCapped() {
        val result = GoalScorer.score(waterTarget, answer("water", number = 3.0))
        assertEquals(GoalOutcome.MET, result.outcome)
        assertEquals(1.0, result.attainment)
    }

    @Test
    @DisplayName("2.5 - AT_MOST 2, answer 3 is MISSED with attainment ABSENT, not zero")
    fun attainmentIsMeaninglessForAtMost() {
        // "How close was I" has no sensible answer for an upper bound, and zero would read as total
        // failure. Absence is the honest representation.
        val result = GoalScorer.score(
            target("coffee", Direction.AT_MOST, value = 2.0),
            answer("coffee", number = 3.0),
        )
        assertEquals(GoalOutcome.MISSED, result.outcome)
        assertNull(result.attainment, "must be absent, not 0.0 and not 1.5")
    }

    @Test
    @DisplayName("attainment is absent for non-numeric directions")
    fun attainmentIsAbsentForBooleanAndSelectDirections() {
        assertNull(GoalScorer.score(target("v", Direction.IS_TRUE), answer("v", bool = false)).attainment)
        assertNull(
            GoalScorer.score(
                target("p", Direction.MUST_INCLUDE, option = "x"),
                answer("p", selections = emptySet()),
            ).attainment
        )
    }

    @Test
    @DisplayName("an EXCLUDED goal carries no attainment")
    fun excludedCarriesNoAttainment() {
        val result = GoalScorer.score(waterTarget, answer = null)
        assertEquals(GoalOutcome.EXCLUDED, result.outcome)
        assertNull(result.attainment)
    }

    @Test
    @DisplayName("hit rate is null, not zero, when nothing was scored")
    fun hitRateIsNullWhenNothingScored() {
        // Distinguishes "never scored" from "scored and always failed". A zero here would put an
        // item into the going-badly panel it never earned.
        val summary = ItemSummary.of(List(3) { GoalScorer.score(waterTarget, answer = null) })
        assertNull(summary.hitRate)
        assertNull(summary.averageAttainment)
        assertEquals(3, summary.excluded)
    }

    @Test
    @DisplayName("hit rate counts met over met plus missed, ignoring exclusions entirely")
    fun exclusionsTouchNeitherSideOfHitRate() {
        val results = listOf(
            GoalScorer.score(waterTarget, answer("water", number = 2.0)), // met
            GoalScorer.score(waterTarget, answer("water", number = 1.0)), // missed
            GoalScorer.score(waterTarget, answer = null), // excluded
        )
        val summary = ItemSummary.of(results)
        assertEquals(0.5, summary.hitRate, "1 met of 2 scored; the exclusion is not a third case")
        assertEquals(1, summary.met)
        assertEquals(1, summary.missed)
        assertEquals(1, summary.excluded)
    }
}
