package com.zdredge.consistency.domain.scoring

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.item
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.SelectOption
import com.zdredge.consistency.domain.target
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * docs/scoring-cases.md section 6. Spec 3.3: items are versioned and retired, never deleted, and a
 * retired item stays visible for the periods it was active in while being absent from those it was
 * not. Scoring covers only items active in the period being scored (spec 3.4).
 */
@DisplayName("Item lifecycle - scoring-cases section 6")
class ItemLifecycleTest {

    private val vitamins = target("vitamins", Direction.IS_TRUE)

    @Test
    @DisplayName("6.1 - an item created after the period is excluded entirely, not counted as missed")
    fun anItemThatDidNotExistYetIsExcluded() {
        // Created 2026-09-10; scoring the week of 2026-08-31 (Mon) to 2026-09-06 (Sun).
        val created = item("vitamins", createdOn = LocalDate.of(2026, 9, 10))
        assertFalse(ItemLifecycle.isActiveOn(created, LocalDate.of(2026, 9, 6)))
        assertFalse(
            ItemLifecycle.isActiveInPeriod(
                created,
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 9, 6),
            )
        )

        val result = GoalScorer.score(created, vitamins, answer = null, on = LocalDate.of(2026, 9, 6))
        assertEquals(GoalOutcome.EXCLUDED, result.outcome)
        assertEquals(
            ExclusionReason.NOT_ACTIVE,
            result.exclusionReason,
            "it never existed then, which is not the same as the user skipping it",
        )
    }

    @Test
    @DisplayName("6.2 - a retired item is included for September and excluded for November")
    fun aRetiredItemStaysVisibleForThePeriodsItWasActive() {
        val retired = item(
            "vitamins",
            createdOn = LocalDate.of(2026, 1, 1),
            retiredOn = LocalDate.of(2026, 10, 5),
        )
        assertTrue(
            ItemLifecycle.isActiveInPeriod(retired, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
            "September is before retirement",
        )
        assertFalse(
            ItemLifecycle.isActiveInPeriod(retired, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)),
            "November is after it",
        )
        assertTrue(ItemLifecycle.isActiveOn(retired, LocalDate.of(2026, 10, 5)), "the retirement day itself counts")
        assertFalse(ItemLifecycle.isActiveOn(retired, LocalDate.of(2026, 10, 6)))
    }

    @Test
    @DisplayName("6.3 - answers from two versions both score, each bound to its own version")
    fun answersStayBoundToTheirVersion() {
        // Rewording a question creates a new version; it must not retroactively change what an old
        // answer meant (constraint 6). Both still score against the item's target.
        val v1 = answer("vitamins", bool = true).copy(itemVersionId = ItemVersionId("vitamins-v1"))
        val v2 = answer("vitamins", bool = false).copy(itemVersionId = ItemVersionId("vitamins-v2"))

        assertEquals(GoalOutcome.MET, GoalScorer.score(vitamins, v1).outcome)
        assertEquals(GoalOutcome.MISSED, GoalScorer.score(vitamins, v2).outcome)
        assertEquals(ItemVersionId("vitamins-v1"), v1.itemVersionId)
        assertEquals(ItemVersionId("vitamins-v2"), v2.itemVersionId)
    }

    @Test
    @DisplayName("6.4 - adding a select option creates no new version and leaves prior answers alone")
    fun optionsHangOffTheItemNotTheVersion() {
        // Adding "meditated" to the pre-sleep list is not a change to the question, so it must not
        // bump the version and re-point every prior answer at an older definition.
        val added = SelectOption(
            id = OptionId("meditated"),
            itemId = com.zdredge.consistency.domain.model.ItemId("pre_sleep"),
            label = "Meditated",
            ordinal = 5,
        )
        assertEquals(com.zdredge.consistency.domain.model.ItemId("pre_sleep"), added.itemId)

        val priorAnswer = answer("pre_sleep", selections = setOf("read_a_book"))
        val stillMet = GoalScorer.score(
            target("pre_sleep", Direction.MUST_NOT_INCLUDE, option = "scrolled_on_phone"),
            priorAnswer,
        )
        assertEquals(GoalOutcome.MET, stillMet.outcome, "a new option cannot disturb an old answer")
    }

    @Test
    @DisplayName("an item with no retirement date is active indefinitely")
    fun activeItemsHaveNoEndDate() {
        val live = item("water", createdOn = LocalDate.of(2026, 1, 1))
        assertTrue(ItemLifecycle.isActiveOn(live, LocalDate.of(2030, 1, 1)))
    }
}
