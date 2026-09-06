package com.zdredge.consistency.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The storage side of the check-in loop.
 *
 * The rules themselves are `:domain`'s and already tested without a device. What needs the real
 * SQLite is the wiring: that generation is idempotent against the unique index, that a week away
 * from the app produces a week of rows rather than none, and that answering a check-in updates the
 * two things that must move together.
 */
@RunWith(AndroidJUnit4::class)
class CheckInLoopRepositoryTest {

    private val zone = ZoneId.of("America/New_York")
    private lateinit var db: ConsistencyDatabase

    private val installDay = LocalDate.of(2026, 9, 1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * A fresh install owes no history. Fabricating missed check-ins for days before the app existed
     * would open the record with a failure that never happened.
     */
    @Test
    fun aFreshInstallGeneratesOnlyTodaysCheckIns() = runBlocking {
        val repo = repoAt("2026-09-01T10:00")

        assertEquals(2, repo.ensureCheckInsExist(installDay))
        assertEquals(
            listOf(Slot.MORNING, Slot.NIGHT),
            repo.checkIns(installDay).map { it.slot },
        )
    }

    @Test
    fun generatingTwiceOnTheSameDayCreatesNothingTheSecondTime() = runBlocking {
        val repo = repoAt("2026-09-01T10:00")
        repo.ensureCheckInsExist(installDay)

        // Idempotent by asking what exists, not by letting the unique index reject a duplicate --
        // relying on a constraint violation as control flow makes a real bug indistinguishable from
        // ordinary re-entry.
        assertEquals(0, repo.ensureCheckInsExist(installDay))
        assertEquals(2, repo.checkIns(installDay).size)
    }

    /**
     * **The case response rate depends on.** Days the user never opened the app must still produce
     * rows, or skipping a week would improve the primary metric rather than damaging it.
     */
    @Test
    fun daysTheAppWasNeverOpenedStillGetTheirCheckIns() = runBlocking {
        repoAt("2026-09-01T10:00").ensureCheckInsExist(installDay)

        val laterRepo = repoAt("2026-09-05T10:00")
        val created = laterRepo.ensureCheckInsExist(installDay.plusDays(4))

        assertEquals("four missed days, two check-ins each", 8, created)
        assertEquals(10, laterRepo.checkIns(installDay, installDay.plusDays(4)).size)
    }

    @Test
    fun generatedCheckInsStartOutPending() = runBlocking {
        val repo = repoAt("2026-09-01T10:00")
        repo.ensureCheckInsExist(installDay)

        assertTrue(repo.checkIns(installDay).all { it.state == CheckInState.PENDING })
    }

    /**
     * The banner offers what is still answerable: due, unanswered, and inside the grace window.
     * A check-in whose time has not come is not outstanding — it has not been missed yet.
     */
    @Test
    fun onlyDueCheckInsAreOutstanding() = runBlocking {
        // 10:00: the 08:00 morning check-in is due, the 21:00 night one is not.
        val repo = repoAt("2026-09-01T10:00")
        repo.ensureCheckInsExist(installDay)

        assertEquals(listOf(Slot.MORNING), repo.outstandingCheckIns(installDay).map { it.slot })
    }

    @Test
    fun answeringACheckInRemovesItFromTheOutstandingList() = runBlocking {
        val repo = repoAt("2026-09-01T10:00")
        repo.ensureCheckInsExist(installDay)

        repo.markCheckInAnswered(installDay, Slot.MORNING, Instant.parse("2026-09-01T14:05:00Z"))

        assertTrue(repo.outstandingCheckIns(installDay).isEmpty())
    }

    /**
     * Yesterday's unanswered check-in is still offered, because backfill runs to the end of the next
     * day (spec §3.2). Anything older is still answerable — a late answer keeps the data — but is no
     * longer outstanding, because it can no longer repair the metric (A1.2) and a banner that never
     * empties is one the user stops reading.
     */
    @Test
    fun yesterdayIsStillOutstandingButTheDayBeforeIsNot() = runBlocking {
        repoAt("2026-09-01T10:00").ensureCheckInsExist(installDay)

        val today = installDay.plusDays(2)
        val repo = repoAt("2026-09-03T10:00")
        repo.ensureCheckInsExist(today)

        val outstanding = repo.outstandingCheckIns(today)
        val days = outstanding.map { it.day }.distinct()

        assertTrue("yesterday must still be offered", today.minusDays(1) in days)
        assertFalse("two days ago is past grace", installDay in days)
    }

    /**
     * Answering must move two things together: the answer is stored, and the check-in it was given
     * in is marked answered. Leaving the second to the caller is how a completed check-in ends up
     * counting as missed.
     */
    @Test
    fun recordingAnAnswerAlsoMarksItsCheckInAnswered() = runBlocking {
        val repo = repoAt("2026-09-01T21:30")
        repo.seedLibraryIfEmpty(installDay)
        repo.ensureCheckInsExist(installDay)

        repo.recordAnswer(mealsAnswer(Capture.IN_WINDOW), installDay, Slot.NIGHT)

        assertEquals(
            CheckInState.ANSWERED,
            repo.checkIns(installDay).single { it.slot == Slot.NIGHT }.state,
        )
        assertEquals(3.0, repo.answer(ItemId("meals"), installDay)!!.valueNumber!!, 0.0)
    }

    /**
     * Scoring-case A2.2: "not yet" is an act of *completing* the check-in. An unresolved deferral
     * costs the goal, never the response rate.
     */
    @Test
    fun deferringStillMarksTheCheckInAnswered() = runBlocking {
        val repo = repoAt("2026-09-01T21:30")
        repo.seedLibraryIfEmpty(installDay)
        repo.ensureCheckInsExist(installDay)

        repo.recordAnswer(mealsAnswer(Capture.PENDING), installDay, Slot.NIGHT)

        assertEquals(
            CheckInState.ANSWERED,
            repo.checkIns(installDay).single { it.slot == Slot.NIGHT }.state,
        )
        assertEquals(listOf(ItemId("meals")), repo.deferrals().map { it.itemId })
    }

    /** The questions come back through `:domain`, in the seeded order. */
    @Test
    fun theNightCheckInAsksTheSeededNightQuestionsInOrder() = runBlocking {
        val repo = repoAt("2026-09-01T21:30")
        repo.seedLibraryIfEmpty(installDay)

        val ids = repo.checkInQuestions(installDay, Slot.NIGHT).map { it.item.id.value }

        assertEquals(
            listOf("meals", "vitamins", "water", "worked_out", "stretched", "coffee",
                "took_time", "mindset", "steps"),
            ids,
        )
    }

    @Test
    fun theMorningCheckInAsksTheSleepQuestionsChronologically() = runBlocking {
        val repo = repoAt("2026-09-02T08:30")
        repo.seedLibraryIfEmpty(installDay)

        val ids = repo.checkInQuestions(installDay.plusDays(1), Slot.MORNING)
            .map { it.item.id.value }

        // The order items.ordinal exists for: by id this would read bedtime, got_up_at, pre_sleep.
        assertEquals(listOf("bedtime", "pre_sleep", "woke_at", "got_up_at"), ids)
    }

    private fun mealsAnswer(capture: Capture) = Answer(
        itemId = ItemId("meals"),
        itemVersionId = ItemVersionId("meals.v1"),
        day = installDay,
        capture = capture,
        submittedAt = Instant.parse("2026-09-02T01:30:00Z"),
        valueNumber = if (capture == Capture.PENDING) null else 3.0,
    )

    private fun repoAt(local: String) = ConsistencyRepository(
        db,
        DayResolver(Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)),
    )
}
