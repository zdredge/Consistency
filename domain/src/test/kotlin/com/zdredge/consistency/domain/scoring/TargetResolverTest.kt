package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.model.ContainerSize
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * docs/scoring-cases.md section 5. Spec constraint 2: targets and container sizes are stored per
 * period with an effective-from date, so raising a target never re-scores closed periods. Collapsing
 * them to a single current value destroys the truthfulness of historical scoring.
 */
@DisplayName("Effective-from resolution over time - scoring-cases section 5")
class TargetResolverTest {

    private val aug1 = LocalDate.of(2026, 8, 1)
    private val sep1 = LocalDate.of(2026, 9, 1)
    private val oct1 = LocalDate.of(2026, 10, 1)
    private val water = ItemId("water")

    /** Water was at least 2 from August, raised to at least 3 from September. */
    private val resolver = TargetResolver(
        listOf(
            target("water", Direction.AT_LEAST, value = 2.0, from = aug1),
            target("water", Direction.AT_LEAST, value = 3.0, from = sep1),
        )
    )

    @Test
    @DisplayName("5.1 - an answer of 2 on 2026-08-15 is scored against 2, and is MET")
    fun augustAnswerScoredAgainstAugustTarget() {
        val inForce = resolver.resolve(water, Period.DAY, LocalDate.of(2026, 8, 15))
        assertEquals(2.0, inForce?.valueNumber)
        assertEquals(
            GoalOutcome.MET,
            DirectionEvaluator.evaluate(inForce!!, answer("water", number = 2.0)),
        )
    }

    @Test
    @DisplayName("5.2 - the same answer of 2 on 2026-09-15 is scored against 3, and is MISSED")
    fun septemberAnswerScoredAgainstRaisedTarget() {
        val inForce = resolver.resolve(water, Period.DAY, LocalDate.of(2026, 9, 15))
        assertEquals(3.0, inForce?.valueNumber)
        assertEquals(
            GoalOutcome.MISSED,
            DirectionEvaluator.evaluate(inForce!!, answer("water", number = 2.0)),
        )
    }

    @Test
    @DisplayName("5.3 - recomputing August after the September raise leaves August unchanged")
    fun raisingATargetDoesNotRescoreClosedPeriods() {
        // The whole point of constraint 2: the later target exists in the same list and must not
        // reach backwards. Resolution is by date, not by "current".
        val august = resolver.resolve(water, Period.DAY, LocalDate.of(2026, 8, 15))
        assertEquals(2.0, august?.valueNumber)
    }

    @Test
    @DisplayName("a date before any effective-from resolves to no target at all")
    fun noTargetBeforeTheFirstEffectiveFrom() {
        assertNull(resolver.resolve(water, Period.DAY, LocalDate.of(2026, 7, 31)))
    }

    @Test
    @DisplayName("resolution is per period, so a daily target is not returned for a weekly lookup")
    fun resolutionIsPerPeriod() {
        assertNull(resolver.resolve(water, Period.WEEK, LocalDate.of(2026, 8, 15)))
    }

    @Test
    @DisplayName("5.4 - an August answer of 2 resolves to 80 oz, not 64")
    fun containerSizeInForceAtTheTime() {
        // 40 oz bottles in August, 32 oz from October. Changing bottles must not rewrite history.
        val sizes = ContainerSizeResolver(
            listOf(
                ContainerSize(water, size = 40.0, unitLabel = "oz", effectiveFrom = aug1),
                ContainerSize(water, size = 32.0, unitLabel = "oz", effectiveFrom = oct1),
            )
        )
        assertEquals(
            80.0,
            sizes.absoluteAmount(water, count = 2.0, on = LocalDate.of(2026, 8, 15)),
        )
        assertEquals(
            64.0,
            sizes.absoluteAmount(water, count = 2.0, on = LocalDate.of(2026, 10, 15)),
            "after the switch the same count is a different absolute amount",
        )
    }
}
