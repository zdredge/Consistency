package com.zdredge.consistency.domain.checkin

import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
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
import java.time.Instant
import java.time.LocalDate

/**
 * Which questions a check-in asks.
 *
 * Each rule here is small and they are easy to get subtly wrong together. Asking about an item
 * retired last month, or silently dropping a deferral the user was promised would come back, are
 * quiet failures rather than crashes — which is why this is a pure function with tests rather than
 * a loop inside a ViewModel.
 */
@DisplayName("Check-in content - spec 1, 3.1, 3.3, constraint 6")
class CheckInContentTest {

    private val monday = LocalDate.of(2026, 8, 24)
    private val tuesday = LocalDate.of(2026, 8, 25)
    private val wednesday = LocalDate.of(2026, 8, 26)
    private val sunday = LocalDate.of(2026, 8, 30)

    private val bedtime = item("bedtime", ordinal = 0)
    private val wokeAt = item("woke_at", ordinal = 1)
    private val meals = item("meals", ordinal = 2)
    private val vitamins = item("vitamins", ordinal = 3)
    private val sawFriends = item("saw_friends", ordinal = 4)
    private val steps = item("steps", ordinal = 5, kind = ItemKind.MEASURED)

    private val allItems = listOf(bedtime, wokeAt, meals, vitamins, sawFriends, steps)
    private val allVersions = listOf(
        version("bedtime", Slot.MORNING, AnswerType.TIME),
        version("woke_at", Slot.MORNING, AnswerType.TIME),
        version("meals", Slot.NIGHT, AnswerType.NUMBER),
        version("vitamins", Slot.NIGHT, AnswerType.BOOL),
        version("saw_friends", Slot.WEEKLY, AnswerType.SINGLE_SELECT),
        version("steps", Slot.NONE, AnswerType.NUMBER),
    )

    @Test
    @DisplayName("the morning check-in asks only the morning items")
    fun theMorningCheckInAsksMorningItems() {
        assertEquals(listOf("bedtime", "woke_at"), contentFor(wednesday, Slot.MORNING).ids())
    }

    @Test
    @DisplayName("the night check-in asks the night items and shows steps read-only")
    fun theNightCheckInAsksNightItemsAndShowsSteps() {
        val entries = contentFor(tuesday, Slot.NIGHT)

        assertEquals(listOf("meals", "vitamins", "steps"), entries.ids())
        assertTrue(entries.single { it.item.id.value == "steps" }.readOnly, "steps is never asked")
        assertTrue(entries.none { it.item.id.value == "meals" && it.readOnly })
    }

    @Test
    @DisplayName("questions come back in their configured order, not by id")
    fun questionsUseTheirOrdinal() {
        // The reason items.ordinal exists: by id, "got up at" would precede "woke at".
        val shuffled = listOf(wokeAt, bedtime)
        assertEquals(
            listOf("bedtime", "woke_at"),
            CheckInContent.forCheckIn(wednesday, Slot.MORNING, shuffled, allVersions).ids(),
        )
    }

    /**
     * Spec §1: the weekly questions are appended to Sunday night's check-in, closing the
     * Monday–Sunday week the moment it ends.
     */
    @Test
    @DisplayName("Sunday night appends the weekly questions to the night ones")
    fun sundayNightIncludesTheWeeklyItems() {
        assertEquals(
            listOf("meals", "vitamins", "saw_friends", "steps"),
            contentFor(sunday, Slot.NIGHT).ids(),
        )
    }

    @Test
    @DisplayName("any other night leaves the weekly questions out")
    fun aWeekdayNightExcludesTheWeeklyItems() {
        assertFalse(contentFor(tuesday, Slot.NIGHT).ids().contains("saw_friends"))
    }

    @Test
    @DisplayName("Sunday morning does not ask the weekly questions either")
    fun sundayMorningExcludesTheWeeklyItems() {
        // They belong to the night check-in specifically; a morning slot would close the week early.
        assertFalse(contentFor(sunday, Slot.MORNING).ids().contains("saw_friends"))
    }

    /**
     * Activity is judged against the day being *answered*, not the day of the check-in. Those differ
     * for the morning check-in, and getting it wrong drops a question about a day the item was live
     * for.
     */
    @Test
    @DisplayName("an item retired yesterday is still asked this morning, about yesterday")
    fun anItemRetiredYesterdayIsStillAskedAboutYesterday() {
        val retiring = listOf(
            bedtime.copy(retiredOn = tuesday),
            wokeAt,
        )

        // The morning check-in of Wednesday answers Tuesday, and bedtime was live on Tuesday.
        assertTrue(
            CheckInContent.forCheckIn(wednesday, Slot.MORNING, retiring, allVersions)
                .ids().contains("bedtime"),
        )
        // By Thursday morning it is answering Wednesday, and bedtime is gone.
        assertFalse(
            CheckInContent.forCheckIn(wednesday.plusDays(1), Slot.MORNING, retiring, allVersions)
                .ids().contains("bedtime"),
        )
    }

    @Test
    @DisplayName("an item created today is not asked about yesterday")
    fun anItemCreatedTodayIsNotAskedAboutYesterday() {
        val fresh = listOf(bedtime.copy(createdOn = wednesday))

        assertTrue(CheckInContent.forCheckIn(wednesday, Slot.MORNING, fresh, allVersions).isEmpty())
    }

    /**
     * Constraint 6: rewording must not retroactively change what an older answer meant, so a
     * backfill asks the wording that was in force on the day being answered.
     */
    @Test
    @DisplayName("a check-in asks the version in force on the day being answered")
    fun theVersionInForceIsTheOneForTheDayAnswered() {
        val versions = listOf(
            version("meals", Slot.NIGHT, AnswerType.NUMBER, prompt = "Meals?", from = monday),
            version(
                "meals", Slot.NIGHT, AnswerType.NUMBER,
                prompt = "How many meals?", from = wednesday, id = "meals.v2",
            ),
        )

        assertEquals(
            "Meals?",
            CheckInContent.forCheckIn(tuesday, Slot.NIGHT, listOf(meals), versions)
                .single().version.prompt,
        )
        assertEquals(
            "How many meals?",
            CheckInContent.forCheckIn(wednesday, Slot.NIGHT, listOf(meals), versions)
                .single().version.prompt,
        )
    }

    /**
     * The promise "not yet" makes is that the question comes back once, in the next morning's
     * check-in, labelled as belonging to the night before (spec §3.2).
     */
    @Test
    @DisplayName("a deferral is carried into the next morning, labelled with the day it belongs to")
    fun aDeferralIsCarriedIntoTheNextMorning() {
        val entries = CheckInContent.forCheckIn(
            wednesday, Slot.MORNING, allItems, allVersions, deferrals = listOf(deferredVitamins),
        )

        val carried = entries.single { it.item.id.value == "vitamins" }
        assertEquals(tuesday, carried.carriedOverFrom)
        assertTrue(carried.isCarriedOver)
    }

    @Test
    @DisplayName("a carried-over question leads the check-in rather than being buried")
    fun carriedOverQuestionsComeFirst() {
        // Burying a deferral under fresh questions is how it quietly becomes a miss.
        val entries = CheckInContent.forCheckIn(
            wednesday, Slot.MORNING, allItems, allVersions, deferrals = listOf(deferredVitamins),
        )

        assertEquals(listOf("vitamins", "bedtime", "woke_at"), entries.ids())
    }

    @Test
    @DisplayName("a deferral is not carried into a night check-in")
    fun deferralsAreNotCarriedIntoTheNight() {
        // "Not yet" resolves in the *next morning's* check-in, per spec 3.2. Offering it again that
        // night would make the deferral open-ended.
        val entries = CheckInContent.forCheckIn(
            tuesday, Slot.NIGHT, allItems, allVersions, deferrals = listOf(deferredVitamins),
        )

        assertTrue(entries.none { it.isCarriedOver })
    }

    @Test
    @DisplayName("a deferral belonging to an older day is not resurrected")
    fun anOlderDeferralIsNotResurrected() {
        // It converts to a missed goal at rollover (A2.1) rather than reappearing indefinitely.
        val stale = deferredVitamins.copy(day = monday)

        val entries = CheckInContent.forCheckIn(
            wednesday, Slot.MORNING, allItems, allVersions, deferrals = listOf(stale),
        )

        assertTrue(entries.none { it.isCarriedOver })
    }

    @Test
    @DisplayName("an already-resolved answer is not carried over")
    fun aResolvedAnswerIsNotCarriedOver() {
        val resolved = deferredVitamins.copy(capture = Capture.IN_WINDOW)

        val entries = CheckInContent.forCheckIn(
            wednesday, Slot.MORNING, allItems, allVersions, deferrals = listOf(resolved),
        )

        assertTrue(entries.none { it.isCarriedOver })
    }

    private val deferredVitamins = Answer(
        itemId = ItemId("vitamins"),
        itemVersionId = ItemVersionId("vitamins.v1"),
        day = tuesday,
        capture = Capture.PENDING,
        submittedAt = Instant.parse("2026-08-26T01:14:00Z"),
    )

    private fun contentFor(day: LocalDate, slot: Slot) =
        CheckInContent.forCheckIn(day, slot, allItems, allVersions)

    private fun List<CheckInEntry>.ids() = map { it.item.id.value }

    private fun item(
        id: String,
        ordinal: Int,
        kind: ItemKind = ItemKind.ASKED,
        createdOn: LocalDate = LocalDate.of(2026, 8, 1),
    ) = Item(ItemId(id), kind, createdOn, ordinal = ordinal)

    private fun version(
        itemId: String,
        slot: Slot,
        answerType: AnswerType,
        prompt: String = itemId,
        from: LocalDate = LocalDate.of(2026, 8, 1),
        id: String = "$itemId.v1",
    ) = ItemVersion(
        id = ItemVersionId(id),
        itemId = ItemId(itemId),
        versionNo = 1,
        prompt = prompt,
        answerType = answerType,
        classification = Classification.GOAL,
        slot = slot,
        effectiveFrom = from,
    )
}
