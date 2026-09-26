package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Item
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersion
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * What counts as a question having an answer.
 *
 * Marking a check-in answered and the Home count of questions left both read this, so a wrong rule
 * here either inflates response rate or tells the user a finished check-in is missing answers.
 */
@DisplayName("Check-in completeness - spec 3.1, 3.3, A2.2")
class CheckInCompletenessTest {

    private val tuesday = LocalDate.of(2026, 8, 25)
    private val wednesday = LocalDate.of(2026, 8, 26)

    private val meals = entry("meals", Slot.NIGHT)
    private val vitamins = entry("vitamins", Slot.NIGHT)
    private val steps = entry("steps", Slot.NONE, readOnly = true)
    private val bedtime = entry("bedtime", Slot.MORNING)

    @Test
    @DisplayName("a skipped question is counted and an answered one is not")
    fun countsSkippedQuestions() {
        val result = CheckInCompleteness.of(listOf(meals, vitamins), tuesday, Slot.NIGHT, answered("meals" to tuesday))

        assertEquals(2, result.asked)
        assertEquals(1, result.unanswered)
        assertTrue(result.responded)
    }

    @Test
    @DisplayName("3.3 - steps are read, not given, so they are never a question left unanswered")
    fun readOnlyEntriesAreNotQuestions() {
        val result = CheckInCompleteness.of(listOf(meals, steps), tuesday, Slot.NIGHT, answered("meals" to tuesday))

        assertEquals(1, result.asked)
        assertEquals(0, result.unanswered)
    }

    @Test
    @DisplayName("a step value alone is not a response")
    fun stepsAloneAreNotAResponse() {
        val result = CheckInCompleteness.of(listOf(meals, steps), tuesday, Slot.NIGHT, answered("steps" to tuesday))

        assertFalse(result.responded)
    }

    @Test
    @DisplayName("A2.2 - a deferral is a row, so it counts as answered")
    fun aDeferralCountsAsAnswered() {
        // The deferral is stored as a value-less PENDING row; asking about the row, not its value, is
        // what keeps "not yet" a response.
        val result = CheckInCompleteness.of(listOf(meals), tuesday, Slot.NIGHT, answered("meals" to tuesday))

        assertEquals(0, result.unanswered)
    }

    @Test
    @DisplayName("3.1 - a sleep item in Wednesday's morning check-in is looked up on Tuesday")
    fun sleepItemsAreLookedUpOnTheNightBefore() {
        val onTuesday = CheckInCompleteness.of(listOf(bedtime), wednesday, Slot.MORNING, answered("bedtime" to tuesday))
        val onWednesday = CheckInCompleteness.of(listOf(bedtime), wednesday, Slot.MORNING, answered("bedtime" to wednesday))

        assertEquals(0, onTuesday.unanswered)
        assertEquals(1, onWednesday.unanswered, "an answer on the check-in's own day is not this question's")
    }

    @Test
    @DisplayName("a carried-over deferral is looked up on the night it was deferred from")
    fun carriedOverEntriesAreLookedUpOnTheirOriginDay() {
        val carried = meals.copy(carriedOverFrom = tuesday)
        val result = CheckInCompleteness.of(listOf(carried), wednesday, Slot.MORNING, answered("meals" to tuesday))

        assertEquals(0, result.unanswered)
    }

    @Test
    @DisplayName("a check-in that asks nothing has not been responded to")
    fun nothingAskedIsNoResponse() {
        assertFalse(CheckInCompleteness.of(listOf(steps), tuesday, Slot.NIGHT) { _, _ -> true }.responded)
    }

    private fun answered(vararg rows: Pair<String, LocalDate>): (ItemId, LocalDate) -> Boolean {
        val set = rows.map { (id, day) -> ItemId(id) to day }.toSet()
        return { id, day -> (id to day) in set }
    }

    private fun entry(id: String, slot: Slot, readOnly: Boolean = false) = CheckInEntry(
        item = Item(
            id = ItemId(id),
            kind = if (readOnly) ItemKind.MEASURED else ItemKind.ASKED,
            createdOn = LocalDate.of(2026, 8, 1),
        ),
        version = ItemVersion(
            id = ItemVersionId("$id-v1"),
            itemId = ItemId(id),
            versionNo = 1,
            prompt = id,
            answerType = AnswerType.NUMBER,
            classification = Classification.GOAL,
            slot = slot,
            effectiveFrom = LocalDate.of(2026, 8, 1),
        ),
        readOnly = readOnly,
    )
}
