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

    /**
     * The library is seeded a week before the test's "install day" so every check-in in range has
     * questions to ask. A check-in that would ask nothing is never expected, which is a rule in its
     * own right and gets its own test below rather than quietly shaping every other one.
     */
    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
        // Block body, not an expression: JUnit 4 requires @Before to return void, and
        // `= runBlocking { ... }` would infer Boolean from seedLibraryIfEmpty and fail to load.
        runBlocking { repoAt("2026-09-01T10:00").seedLibraryIfEmpty(installDay.minusDays(7)) }
    }

    @After
    fun tearDown() = db.close()

    /**
     * A fresh install owes no history. Fabricating missed check-ins for days before the app existed
     * would open the record with a failure that never happened.
     */
    @Test
    fun generationStartsAtTodayAndReachesNoFurtherBack() = runBlocking {
        val repo = repoAt("2026-09-01T10:00")

        assertEquals(2, repo.ensureCheckInsExist(installDay))
        assertEquals(
            listOf(Slot.MORNING, Slot.NIGHT),
            repo.checkIns(installDay).map { it.slot },
        )
        assertTrue(repo.checkIns(installDay.minusDays(1)).isEmpty())
    }

    /**
     * **The install-day case, found by installing the app and looking at it.**
     *
     * On the day the library is seeded, the morning check-in covers *yesterday* — when no item
     * existed. Generating it would put an unanswerable row into the response-rate denominator, so a
     * brand-new user opens the app already counted against. A check-in with nothing to ask was never
     * expected.
     */
    @Test
    fun onTheDayTheLibraryIsSeededOnlyTheNightCheckInIsExpected() = runBlocking {
        val freshDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
        val repo = ConsistencyRepository(
            freshDb,
            DayResolver(
                Clock.fixed(LocalDateTime.parse("2026-09-01T10:00").atZone(zone).toInstant(), zone),
            ),
        )
        try {
            repo.seedLibraryIfEmpty(installDay)
            repo.ensureCheckInsExist(installDay)

            assertEquals(listOf(Slot.NIGHT), repo.checkIns(installDay).map { it.slot })
        } finally {
            freshDb.close()
        }
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
     * **Answers are written as they are given; only finishing marks the check-in answered.**
     *
     * M4 paired the two, on the reasoning that leaving the second to the caller is how a completed
     * check-in ends up counting as missed. M4.5 writes each answer as the user leaves its question,
     * which makes the pairing wrong: the first answer would mark the check-in answered, and the
     * primary metric would be satisfied by opening a check-in and tapping one chip. Response rate
     * measures showing up, and showing up has to mean reaching the end.
     */
    @Test
    fun recordingAnAnswerStoresItWithoutMarkingTheCheckInAnswered() = runBlocking {
        val repo = repoAt("2026-09-01T21:30")
        repo.ensureCheckInsExist(installDay)

        repo.recordAnswer(mealsAnswer(Capture.IN_WINDOW), installDay, Slot.NIGHT)

        assertEquals(3.0, repo.answer(ItemId("meals"), installDay)!!.valueNumber!!, 0.0)
        assertEquals(
            "the answer is kept; the check-in is not yet answered",
            CheckInState.PENDING,
            repo.checkIns(installDay).single { it.slot == Slot.NIGHT }.state,
        )
    }

    /**
     * Abandoning a check-in halfway keeps every answer given and leaves the check-in honestly
     * outstanding, so the banner still offers it.
     */
    @Test
    fun anAbandonedCheckInKeepsItsAnswersAndStaysOutstanding() = runBlocking {
        val repo = repoAt("2026-09-01T21:30")
        repo.ensureCheckInsExist(installDay)

        repo.recordAnswer(mealsAnswer(Capture.IN_WINDOW), installDay, Slot.NIGHT)

        assertEquals(3.0, repo.answer(ItemId("meals"), installDay)!!.valueNumber!!, 0.0)
        assertTrue(
            "an unfinished check-in is still offered",
            repo.outstandingCheckIns(installDay).any { it.slot == Slot.NIGHT },
        )
    }

    @Test
    fun finishingTheSetIsWhatMarksTheCheckInAnswered() = runBlocking {
        val repo = repoAt("2026-09-01T21:30")
        repo.ensureCheckInsExist(installDay)
        repo.recordAnswer(mealsAnswer(Capture.IN_WINDOW), installDay, Slot.NIGHT)

        repo.markCheckInAnswered(installDay, Slot.NIGHT, Instant.parse("2026-09-02T01:35:00Z"))

        assertEquals(
            CheckInState.ANSWERED,
            repo.checkIns(installDay).single { it.slot == Slot.NIGHT }.state,
        )
        // Only the night one. That morning's check-in is still legitimately outstanding, which is
        // why this asserts the slot rather than an empty list.
        assertTrue(
            "a finished check-in stops being offered",
            repo.outstandingCheckIns(installDay).none { it.slot == Slot.NIGHT },
        )
    }

    /**
     * **Scoring-case A2.2, which survives the M4.5 change untouched.** "Not yet" is an act of
     * completing the check-in, so a check-in finished with an unresolved deferral in it stays
     * ANSWERED: the deferral costs the *goal*, never the response rate.
     *
     * What moved is only the trigger — finishing the set marks it answered, rather than the deferral
     * itself doing so. The rule this test exists to protect is unchanged, and dropping it while
     * rewriting for the new trigger would have been easy and silent.
     */
    @Test
    fun aCheckInFinishedWithAnUnresolvedDeferralStaysAnswered() = runBlocking {
        val repo = repoAt("2026-09-01T21:30")
        repo.ensureCheckInsExist(installDay)

        repo.recordAnswer(mealsAnswer(Capture.PENDING), installDay, Slot.NIGHT)
        repo.markCheckInAnswered(installDay, Slot.NIGHT, Instant.parse("2026-09-02T01:35:00Z"))

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

    // ---- The deferral cycle, end to end ------------------------------------------------------

    /**
     * **"Not yet", resolved the next morning.** The full promise: the question comes back, labelled
     * as belonging to the night it was deferred from, and resolving it there costs nothing
     * (scoring-cases 3.3, A2.2).
     */
    @Test
    fun aDeferralComesBackTheNextMorningAndResolvesAsInWindow() = runBlocking {
        val night = repoAt("2026-09-01T21:30")
        night.ensureCheckInsExist(installDay)
        night.recordAnswer(mealsAnswer(Capture.PENDING), installDay, Slot.NIGHT)

        // Next morning: the deferred question is carried in, dated the night it came from.
        val morning = repoAt("2026-09-02T08:30")
        morning.ensureCheckInsExist(installDay.plusDays(1))
        val carried = morning.checkInQuestions(installDay.plusDays(1), Slot.MORNING)
            .single { it.item.id == ItemId("meals") }

        assertEquals(installDay, carried.carriedOverFrom)
        assertTrue("a carried-over question is never deferrable again", !carried.canDefer)

        // Resolving it replaces the pending row in place and costs nothing.
        morning.recordAnswer(
            mealsAnswer(Capture.IN_WINDOW),
            installDay.plusDays(1),
            Slot.MORNING,
        )

        val resolved = morning.answer(ItemId("meals"), installDay)!!
        assertEquals(Capture.IN_WINDOW, resolved.capture)
        assertEquals(3.0, resolved.valueNumber!!, 0.0)
        assertTrue("the deferral is gone once resolved", morning.deferrals().isEmpty())
    }

    @Test
    fun anUnresolvedDeferralIsStillCarriedButOnlyIntoTheNextMorning() = runBlocking {
        val night = repoAt("2026-09-01T21:30")
        night.ensureCheckInsExist(installDay)
        night.recordAnswer(mealsAnswer(Capture.PENDING), installDay, Slot.NIGHT)

        // Two mornings later it is not resurrected -- it becomes a missed goal at rollover (A2.1)
        // rather than reappearing indefinitely.
        val later = repoAt("2026-09-03T08:30")
        later.ensureCheckInsExist(installDay.plusDays(2))

        assertTrue(
            later.checkInQuestions(installDay.plusDays(2), Slot.MORNING).none { it.isCarriedOver },
        )
    }

    /** Backfilling yesterday's check-in today records BACKFILLED, permanently (spec §3.2). */
    @Test
    fun answeringYesterdaysCheckInTodayRecordsAsBackfilled() = runBlocking {
        repoAt("2026-09-01T10:00").ensureCheckInsExist(installDay)

        val today = repoAt("2026-09-02T19:00")
        today.ensureCheckInsExist(installDay.plusDays(1))
        today.recordAnswer(
            mealsAnswer(Capture.BACKFILLED),
            installDay,
            Slot.NIGHT,
        )
        // Finishing the set is what repairs the check-in, exactly as it is for a same-day one.
        today.markCheckInAnswered(installDay, Slot.NIGHT, Instant.parse("2026-09-02T23:05:00Z"))

        assertEquals(Capture.BACKFILLED, today.answer(ItemId("meals"), installDay)!!.capture)
        assertEquals(
            CheckInState.ANSWERED,
            today.checkIns(installDay).single { it.slot == Slot.NIGHT }.state,
        )
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
