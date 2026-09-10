package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Steps against their targets — the seeded 10,000 daily and 70,000 weekly (spec §4).
 *
 * The arithmetic is the same as any other goal's. What is specific here is what happens on the two
 * days that are not ordinary: one with no data, and one the app has refused to count.
 */
class MeasuredScorerTest {

    private val steps = ItemId("steps")
    private val day = LocalDate.of(2026, 9, 10)

    private val daily = Target(
        itemId = steps,
        period = Period.DAY,
        direction = Direction.AT_LEAST,
        valueNumber = 10_000.0,
        effectiveFrom = LocalDate.of(2026, 9, 1),
    )
    private val weekly = daily.copy(period = Period.WEEK, valueNumber = 70_000.0)

    private fun value(
        amount: Double,
        state: MeasuredState = MeasuredState.PROVISIONAL,
        on: LocalDate = day,
    ) = MeasuredValue(itemId = steps, day = on, value = amount, state = state)

    @Test
    @DisplayName("a day over its target is met, and reports how far over it got")
    fun metReportsAttainment() {
        val result = MeasuredScorer.score(daily, value(11_240.0))

        assertEquals(GoalOutcome.MET, result.outcome)
        assertEquals(1.0, result.attainment)
    }

    @Test
    @DisplayName("5.4 - a day under its target reports how close it came, not merely that it failed")
    fun missedStillReportsAttainment() {
        // The figure spec 5.4 wants beside hit rate. GoalScorer.scoreValue withholds it; a single
        // measured day has no missing denominator, so withholding it here would be a pure loss.
        val result = MeasuredScorer.score(daily, value(8_400.0))

        assertEquals(GoalOutcome.MISSED, result.outcome)
        assertEquals(0.84, result.attainment!!, 1e-9)
    }

    @Test
    @DisplayName("a day with no data is excluded, never missed")
    fun noDataIsExcluded() {
        // No record read is not zero steps. Scoring it as a miss would charge the user for the
        // platform not having synced.
        val result = MeasuredScorer.score(daily, null)

        assertEquals(GoalOutcome.EXCLUDED, result.outcome)
        assertEquals(ExclusionReason.NO_ANSWER, result.exclusionReason)
    }

    @Test
    @DisplayName("a conflicted day is excluded, and says why")
    fun conflictedIsExcluded() {
        // The user walked whatever they walked. A day the app cannot count honestly is the app's
        // problem, and marking it missed would turn a source misconfiguration into a broken run.
        val result = MeasuredScorer.score(daily, value(21_220.0, MeasuredState.CONFLICTED))

        assertEquals(GoalOutcome.EXCLUDED, result.outcome)
        assertEquals(ExclusionReason.SOURCE_CONFLICT, result.exclusionReason)
    }

    @Test
    @DisplayName("O4 - a provisional value scores exactly like a frozen one")
    fun provisionalScoresTheSameAsFrozen() {
        // O4 makes a value revisable, not unusable. Withholding a score until it froze would leave
        // today and yesterday permanently blank.
        assertEquals(
            MeasuredScorer.score(daily, value(11_240.0, MeasuredState.FROZEN)).outcome,
            MeasuredScorer.score(daily, value(11_240.0, MeasuredState.PROVISIONAL)).outcome,
        )
    }

    @Test
    @DisplayName("3.4 - the week is scored on its own target, independently of the days")
    fun theWeekSumsItsDays() {
        // 70,000 across three big walks is a different week from 10,000 every day, and the two
        // targets are reported separately rather than collapsed (spec 3.4).
        val week = (0..6).map { value(10_500.0, on = day.plusDays(it.toLong())) }

        val result = MeasuredScorer.scoreWeek(weekly, week)

        assertEquals(GoalOutcome.MET, result.outcome)
    }

    @Test
    @DisplayName("a conflicted day contributes nothing to the week rather than zero")
    fun conflictedDaysAreLeftOutOfTheWeek() {
        // Understating a week containing a conflict is the right direction to be wrong in: the
        // alternative is inventing steps. The day's own exclusion is what makes the gap visible.
        val week = listOf(
            value(30_000.0),
            value(99_000.0, MeasuredState.CONFLICTED, on = day.plusDays(1)),
        )

        val result = MeasuredScorer.scoreWeek(weekly, week)

        assertEquals(GoalOutcome.MISSED, result.outcome)
        // 30,000 of 70,000 -- the 99,000 conflicted day is absent, not added and not zeroed.
        assertEquals(30_000.0 / 70_000.0, result.attainment!!, 1e-9)
    }
}
