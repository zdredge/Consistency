package com.zdredge.consistency.domain.detail

import com.zdredge.consistency.domain.answer
import com.zdredge.consistency.domain.history
import com.zdredge.consistency.domain.item
import com.zdredge.consistency.domain.measured
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ExclusionReason
import com.zdredge.consistency.domain.model.GoalOutcome
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.option
import com.zdredge.consistency.domain.target
import com.zdredge.consistency.domain.version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * What one day of one item amounts to.
 *
 * **Four kinds of blank.** Most of these tests are about telling them apart, because the app currently
 * has three days of data and five weeks of chart: getting this wrong would show the user a month of
 * failures they never had. The rest pin the rules the scorers already hold, so that the square drawn
 * on a calendar and the number counted in the figures can never be two different judgements.
 */
@DisplayName("Judging one day")
class DayCellsTest {

    /** A Wednesday. Grace reaches back through Tuesday, and one day further for a morning item. */
    private val today = LocalDate.of(2026, 9, 9)

    private fun cells(history: com.zdredge.consistency.domain.detail.ItemHistory, vararg days: LocalDate) =
        DayCells.of(history, days.toList(), today).associateBy { it.day }

    private fun cell(history: com.zdredge.consistency.domain.detail.ItemHistory, day: LocalDate) =
        DayCells.of(history, listOf(day), today).single()

    // ---------------------------------------------------------------- the four kinds of blank

    @Test
    @DisplayName("6.1 - a day before the item existed is not a day it skipped")
    fun beforeCreation() {
        val vitamins = item("vitamins", createdOn = today.minusDays(2))
        val history = history(
            item = vitamins,
            version = version("vitamins", AnswerType.BOOL),
            targets = listOf(target("vitamins", Direction.IS_TRUE)),
        )

        assertEquals(DayState.NOT_ACTIVE, cell(history, today.minusDays(3)).state)
        assertNull(cell(history, today.minusDays(3)).result, "not a goal instance at all")
        assertEquals(DayState.NOT_ANSWERED, cell(history, today.minusDays(2)).state)
    }

    @Test
    @DisplayName("6.2 - a retired item's days stop on the day it went")
    fun afterRetirement() {
        val history = history(
            item = item("vitamins", createdOn = today.minusDays(10), retiredOn = today.minusDays(3)),
            version = version("vitamins", AnswerType.BOOL),
            targets = listOf(target("vitamins", Direction.IS_TRUE)),
        )

        assertEquals(DayState.NOT_ANSWERED, cell(history, today.minusDays(3)).state)
        assertEquals(DayState.NOT_ACTIVE, cell(history, today.minusDays(2)).state)
    }

    @Test
    @DisplayName("3.1 - tonight is not a blank for a morning item; it is answered tomorrow")
    fun tonightIsFutureForAMorningItem() {
        val history = history(
            item = item("bedtime", createdOn = today.minusDays(10)),
            version = version("bedtime", AnswerType.TIME, Slot.MORNING, Classification.OBSERVATION),
        )

        assertEquals(DayState.FUTURE, cell(history, today).state)
        assertEquals(DayState.OPEN, cell(history, today.minusDays(1)).state)
    }

    @Test
    @DisplayName("a morning item reaches one night further back, because grace runs on the check-in")
    fun graceRunsOnTheCheckIn() {
        // The answer for two nights ago was written by *yesterday's* check-in, which is still open.
        // Asking Grace about the answer's own day would close a window that is demonstrably open.
        val morning = history(
            item = item("bedtime", createdOn = today.minusDays(10)),
            version = version("bedtime", AnswerType.TIME, Slot.MORNING, Classification.OBSERVATION),
        )
        val night = history(
            item = item("meals", createdOn = today.minusDays(10)),
            version = version("meals", AnswerType.NUMBER, Slot.NIGHT),
        )

        assertEquals(DayState.OPEN, cell(morning, today.minusDays(2)).state)
        assertEquals(DayState.NOT_ANSWERED, cell(morning, today.minusDays(3)).state)

        assertEquals(DayState.OPEN, cell(night, today.minusDays(1)).state)
        assertEquals(DayState.NOT_ANSWERED, cell(night, today.minusDays(2)).state)
    }

    @Test
    @DisplayName("1.13 - silence is excluded, never missed")
    fun silenceIsNotAMiss() {
        val history = history(
            item = item("vitamins", createdOn = today.minusDays(10)),
            version = version("vitamins", AnswerType.BOOL),
            targets = listOf(target("vitamins", Direction.IS_TRUE)),
        )

        val cell = cell(history, today.minusDays(4))
        assertEquals(DayState.NOT_ANSWERED, cell.state)
        assertEquals(GoalOutcome.EXCLUDED, cell.result?.outcome)
        assertEquals(ExclusionReason.NO_ANSWER, cell.result?.exclusionReason)
    }

    @Test
    @DisplayName("5.3 - a day that can still be answered is open, not missed")
    fun anOpenDayIsNotAMiss() {
        val history = history(
            item = item("vitamins", createdOn = today.minusDays(10)),
            version = version("vitamins", AnswerType.BOOL),
            targets = listOf(target("vitamins", Direction.IS_TRUE)),
        )

        val cell = cell(history, today)
        assertEquals(DayState.OPEN, cell.state)
        assertEquals(ExclusionReason.PERIOD_OPEN, cell.result?.exclusionReason)
    }

    // ---------------------------------------------------------------- deferrals

    @Test
    @DisplayName("A2.1 - a deferral is excluded while the morning is still ahead, and missed after")
    fun aDeferralConvertsAtTheGraceBoundary() {
        val history = history(
            item = item("meals", createdOn = today.minusDays(10)),
            version = version("meals", AnswerType.NUMBER),
            targets = listOf(target("meals", Direction.AT_LEAST, value = 3.0)),
            answers = listOf(
                answer("meals", day = today.minusDays(1), capture = Capture.PENDING),
                answer("meals", day = today.minusDays(3), capture = Capture.PENDING),
            ),
        )

        val stillOpen = cell(history, today.minusDays(1))
        assertEquals(DayState.DEFERRED, stillOpen.state)
        assertEquals(ExclusionReason.PERIOD_OPEN, stillOpen.result?.exclusionReason)

        val unresolved = cell(history, today.minusDays(3))
        assertEquals(DayState.MISSED, unresolved.state)
        assertEquals(GoalOutcome.MISSED, unresolved.result?.outcome)
    }

    @Test
    @DisplayName("a deferred day never carries a value, however stale the row")
    fun aDeferralCarriesNoValue() {
        // Deferring clears the value in the check-in, so this row cannot occur today. The rule is here
        // anyway: whatever a pending row holds, the user declined to give it, and drawing it would put
        // a number on the chart nobody ever answered.
        val history = history(
            item = item("meals", createdOn = today.minusDays(10)),
            version = version("meals", AnswerType.NUMBER),
            targets = listOf(target("meals", Direction.AT_LEAST, value = 3.0)),
            answers = listOf(answer("meals", day = today.minusDays(1), number = 5.0, capture = Capture.PENDING)),
        )

        val cell = cell(history, today.minusDays(1))
        assertNull(cell.value)
        assertTrue(cell.marks.deferred)
    }

    @Test
    @DisplayName("an unresolved deferral on a weekly-targeted item is unanswered, not missed")
    fun aDeferralWithNoDailyGoalToMiss() {
        // Stretching is targeted by the week, so there is no daily goal to have missed -- and the week
        // counts the day as unobserved, which is where the cost of the deferral actually lands.
        val history = history(
            item = item("stretched", createdOn = today.minusDays(10)),
            version = version("stretched", AnswerType.BOOL),
            targets = listOf(target("stretched", Direction.AT_LEAST, value = 6.0, period = Period.WEEK)),
            answers = listOf(answer("stretched", day = today.minusDays(3), capture = Capture.PENDING)),
        )

        val cell = cell(history, today.minusDays(3))
        assertEquals(DayState.NOT_ANSWERED, cell.state)
        assertNull(cell.result)
        assertTrue(cell.marks.deferred, "still recognisably a deferral in the table")
    }

    // ---------------------------------------------------------------- answered days

    @Test
    @DisplayName("10.3 - no opportunity is excluded before the direction is applied")
    fun noOpportunityBeatsTheTarget() {
        val history = history(
            item = item("took_time", createdOn = today.minusDays(10)),
            version = version("took_time", AnswerType.SINGLE_SELECT),
            options = listOf(
                option("took_time", "took_time.yes", 0),
                option("took_time", "took_time.no_opportunity", 2, noOpportunity = true),
            ),
            targets = listOf(target("took_time", Direction.MUST_INCLUDE, option = "took_time.yes")),
            answers = listOf(
                answer("took_time", day = today.minusDays(3), selections = setOf("took_time.no_opportunity")),
            ),
        )

        val cell = cell(history, today.minusDays(3))
        assertEquals(DayState.NO_OPPORTUNITY, cell.state)
        assertEquals(ExclusionReason.NO_OPPORTUNITY, cell.result?.exclusionReason)
    }

    @Test
    @DisplayName("a no to a weekly goal is recorded, not a missed day")
    fun aNoWithNoDailyTarget() {
        // "Did you stretch?" is asked every night and targeted six times a week. A no on a Tuesday is
        // a recorded no; only the week can fall short.
        val history = history(
            item = item("stretched", createdOn = today.minusDays(10)),
            version = version("stretched", AnswerType.BOOL),
            targets = listOf(target("stretched", Direction.AT_LEAST, value = 6.0, period = Period.WEEK)),
            answers = listOf(answer("stretched", day = today.minusDays(3), bool = false)),
        )

        val cell = cell(history, today.minusDays(3))
        assertEquals(DayState.RECORDED, cell.state)
        assertNull(cell.result)
        assertEquals(DayValue.YesNo(false), cell.value)
    }

    @Test
    @DisplayName("an observation is recorded, never scored")
    fun anObservationIsNeverScored() {
        val history = history(
            item = item("mindset", createdOn = today.minusDays(10)),
            version = version("mindset", AnswerType.SCALE, Slot.NIGHT, Classification.OBSERVATION),
            answers = listOf(answer("mindset", day = today.minusDays(3), scale = 2)),
        )

        val cell = cell(history, today.minusDays(3))
        assertEquals(DayState.RECORDED, cell.state)
        assertEquals(DayValue.Rating(2), cell.value)
        assertNull(cell.result)
    }

    @Test
    @DisplayName("met and missed, with attainment kept alongside")
    fun scoredDays() {
        val history = history(
            item = item("water", createdOn = today.minusDays(10)),
            version = version("water", AnswerType.NUMBER),
            targets = listOf(target("water", Direction.AT_LEAST, value = 2.0)),
            answers = listOf(
                answer("water", day = today.minusDays(3), number = 2.0),
                answer("water", day = today.minusDays(4), number = 1.5),
            ),
        )

        assertEquals(DayState.MET, cell(history, today.minusDays(3)).state)

        val short = cell(history, today.minusDays(4))
        assertEquals(DayState.MISSED, short.state)
        assertEquals(0.75, short.result?.attainment)
    }

    @Test
    @DisplayName("how an answer was recorded travels with it, separately from what it says")
    fun marks() {
        val history = history(
            item = item("meals", createdOn = today.minusDays(10)),
            version = version("meals", AnswerType.NUMBER),
            targets = listOf(target("meals", Direction.AT_LEAST, value = 3.0)),
            answers = listOf(
                answer("meals", day = today.minusDays(3), number = 3.0, capture = Capture.BACKFILLED, note = "away"),
                answer("meals", day = today.minusDays(4), number = 3.0, capture = Capture.LATE),
                answer("meals", day = today.minusDays(5), number = 3.0, editedAt = Instant.EPOCH),
            ),
        )

        val cells = cells(history, today.minusDays(3), today.minusDays(4), today.minusDays(5))

        assertTrue(cells.getValue(today.minusDays(3)).marks.backfilled)
        assertTrue(cells.getValue(today.minusDays(3)).marks.hasNote)
        assertEquals("away", cells.getValue(today.minusDays(3)).note)
        assertTrue(cells.getValue(today.minusDays(4)).marks.late)
        assertTrue(cells.getValue(today.minusDays(5)).marks.edited)
        // A1.4: a late answer still counts for the goal. Only the run and the check-in refuse it.
        assertEquals(DayState.MET, cells.getValue(today.minusDays(4)).state)
    }

    @Test
    @DisplayName("the value is read through the version's answer type, not by hunting for a column")
    fun valuesFollowTheVersion() {
        val history = history(
            item = item("bedtime", createdOn = today.minusDays(10)),
            version = version("bedtime", AnswerType.TIME, Slot.MORNING, Classification.OBSERVATION),
            answers = listOf(
                // Both columns populated. Only the one the version declares may be drawn.
                answer("bedtime", day = today.minusDays(3), time = LocalTime.of(23, 30), number = 9.0),
            ),
        )

        assertEquals(DayValue.TimeOfDay(LocalTime.of(23, 30)), cell(history, today.minusDays(3)).value)
    }

    // ---------------------------------------------------------------- weekly questions

    @Test
    @DisplayName("a weekly question is judged against its weekly target, on the Sunday it is answered")
    fun weeklyQuestionsUseTheWeeklyTarget() {
        // The target sits on (item, WEEK). Resolving the daily period would find nothing and the
        // answer would be drawn as merely recorded -- an answered goal showing no verdict at all.
        val sunday = LocalDate.of(2026, 9, 6)
        val history = history(
            item = item("saw_friends", createdOn = sunday.minusDays(30)),
            version = version("saw_friends", AnswerType.SINGLE_SELECT, Slot.WEEKLY),
            options = listOf(
                option("saw_friends", "saw_friends.yes", 0),
                option("saw_friends", "saw_friends.no_opportunity", 2, noOpportunity = true),
            ),
            targets = listOf(
                target("saw_friends", Direction.MUST_INCLUDE, option = "saw_friends.yes", period = Period.WEEK),
            ),
            answers = listOf(answer("saw_friends", day = sunday, selections = setOf("saw_friends.yes"))),
        )

        assertEquals(DayState.MET, cell(history, sunday).state)
    }

    @Test
    @DisplayName("10.6 - a weekly no-opportunity excludes the week rather than missing it")
    fun weeklyNoOpportunity() {
        val sunday = LocalDate.of(2026, 9, 6)
        val history = history(
            item = item("invited_someone", createdOn = sunday.minusDays(30)),
            version = version("invited_someone", AnswerType.SINGLE_SELECT, Slot.WEEKLY),
            options = listOf(
                option("invited_someone", "invited_someone.yes", 0),
                option("invited_someone", "invited_someone.no_opportunity", 2, noOpportunity = true),
            ),
            targets = listOf(
                target("invited_someone", Direction.MUST_INCLUDE, option = "invited_someone.yes", period = Period.WEEK),
            ),
            answers = listOf(
                answer("invited_someone", day = sunday, selections = setOf("invited_someone.no_opportunity")),
            ),
        )

        val cell = cell(history, sunday)
        assertEquals(DayState.NO_OPPORTUNITY, cell.state)
        assertEquals(ExclusionReason.NO_OPPORTUNITY, cell.result?.exclusionReason)
    }

    // ---------------------------------------------------------------- measured days

    @Test
    @DisplayName("a day two sources reported is excluded and carries no figure")
    fun conflictedSteps() {
        val history = history(
            item = item("steps", createdOn = today.minusDays(10), kind = ItemKind.MEASURED),
            version = version("steps", AnswerType.NUMBER, Slot.NONE),
            targets = listOf(target("steps", Direction.AT_LEAST, value = 8_000.0)),
            measured = listOf(
                measured(day = today.minusDays(3), value = 17_000.0, state = MeasuredState.CONFLICTED),
            ),
        )

        val cell = cell(history, today.minusDays(3))
        assertEquals(DayState.CONFLICTED, cell.state)
        assertNull(cell.value, "the column holds two sources' total, which is not a step count")
        assertEquals(ExclusionReason.SOURCE_CONFLICT, cell.result?.exclusionReason)
    }

    @Test
    @DisplayName("O4 - a provisional day is scored like any other, and says that it is provisional")
    fun provisionalSteps() {
        val history = history(
            item = item("steps", createdOn = today.minusDays(10), kind = ItemKind.MEASURED),
            version = version("steps", AnswerType.NUMBER, Slot.NONE),
            targets = listOf(target("steps", Direction.AT_LEAST, value = 8_000.0)),
            measured = listOf(
                measured(day = today.minusDays(1), value = 9_100.0, state = MeasuredState.PROVISIONAL),
            ),
        )

        val cell = cell(history, today.minusDays(1))
        assertEquals(DayState.MET, cell.state)
        assertTrue(cell.marks.provisional)
        assertEquals(DayValue.Amount(9_100.0), cell.value)
    }

    @Test
    @DisplayName("a step day nothing was read for is excluded, because that is not zero steps")
    fun missingSteps() {
        val history = history(
            item = item("steps", createdOn = today.minusDays(10), kind = ItemKind.MEASURED),
            version = version("steps", AnswerType.NUMBER, Slot.NONE),
            targets = listOf(target("steps", Direction.AT_LEAST, value = 8_000.0)),
        )

        val old = cell(history, today.minusDays(4))
        assertEquals(DayState.NOT_ANSWERED, old.state)
        assertEquals(ExclusionReason.NO_ANSWER, old.result?.exclusionReason)
        // Today is still filling in, and the rollover re-reads recent days.
        assertEquals(DayState.OPEN, cell(history, today).state)
    }
}
