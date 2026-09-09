package com.zdredge.consistency.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The rollover against real SQLite.
 *
 * The rule itself is `RolloverPlanner`'s and is already proven without a device. What needs the real
 * database is that the plan is actually *written* — that a check-in genuinely moves to `MISSED`, a
 * transition nothing in this app had ever performed before M5, and that the run leaves a record
 * behind. Architecture §8 rates a silently failing rollover as high-impact and invisible; a test
 * that only proved the arithmetic would miss exactly that failure.
 */
@RunWith(AndroidJUnit4::class)
class RolloverRepositoryTest {

    private val zone = ZoneId.of("America/New_York")
    private lateinit var db: ConsistencyDatabase

    private val installDay = LocalDate.of(2026, 9, 1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
        runBlocking { repoAt("2026-09-01T10:00").seedLibraryIfEmpty(installDay.minusDays(7)) }
    }

    @After
    fun tearDown() = db.close()

    /**
     * **The transition this milestone exists for.** Before M5 nothing ever wrote `MISSED`, so an
     * unanswered check-in stayed `PENDING` for ever and response rate could not fall.
     */
    @Test
    fun aCheckInPastItsGraceIsMarkedMissed() = runBlocking {
        repoAt("2026-09-01T10:00").ensureCheckInsExist(installDay)

        // Three days later: install day is well past the end of the next day.
        repoAt("2026-09-04T04:05").runRollover(installDay.plusDays(3))

        assertTrue(
            "every install-day check-in should be missed",
            repoAt("2026-09-04T04:05").checkIns(installDay)
                .all { it.state == CheckInState.MISSED },
        )
    }

    /**
     * The boundary, from the other side. Yesterday is still backfillable, and marking it missed would
     * contradict the banner still offering it and break a run the user could yet save (4.3).
     */
    @Test
    fun yesterdayIsLeftAloneBecauseItIsStillAnswerable() = runBlocking {
        repoAt("2026-09-01T10:00").ensureCheckInsExist(installDay)

        repoAt("2026-09-02T04:05").runRollover(installDay.plusDays(1))

        assertTrue(
            repoAt("2026-09-02T04:05").checkIns(installDay)
                .all { it.state == CheckInState.PENDING },
        )
    }

    @Test
    fun anAnsweredCheckInIsNeverMissedHoweverOld() = runBlocking {
        val repo = repoAt("2026-09-01T21:30")
        repo.ensureCheckInsExist(installDay)
        repo.markCheckInAnswered(installDay, Slot.NIGHT, repo.let { dayResolverAt("2026-09-01T21:30").now() })

        repoAt("2026-09-10T04:05").runRollover(installDay.plusDays(9))

        assertEquals(
            CheckInState.ANSWERED,
            repoAt("2026-09-10T04:05").checkIns(installDay).single { it.slot == Slot.NIGHT }.state,
        )
    }

    /**
     * The device can be off at 04:00 and no document says what then. One run has to resolve every
     * day it slept through, or a missed run becomes a permanent hole in the denominator.
     */
    @Test
    fun oneRunCatchesUpOnEveryDayTheJobDidNotRun() = runBlocking {
        repoAt("2026-09-01T10:00").ensureCheckInsExist(installDay)

        val today = installDay.plusDays(5)
        val outcome = repoAt("2026-09-06T04:05").runRollover(today)

        // It generated the days nobody opened the app on, and closed the ones past grace.
        assertEquals("five days of two check-ins", 10, outcome.checkInsCreated)
        assertTrue("everything up to the grace boundary is closed", outcome.checkInsMissed >= 8)
        assertTrue(
            "today and yesterday stay open",
            repoAt("2026-09-06T04:05").checkIns(today.minusDays(1), today)
                .all { it.state == CheckInState.PENDING },
        )
    }

    @Test
    fun runningTwiceInADayChangesNothingTheSecondTime() = runBlocking {
        repoAt("2026-09-01T10:00").ensureCheckInsExist(installDay)
        val today = installDay.plusDays(3)

        val first = repoAt("2026-09-04T04:05").runRollover(today)
        val second = repoAt("2026-09-04T09:00").runRollover(today)

        assertTrue("the first run has work to do", first.checkInsMissed > 0)
        assertEquals("the second has none", 0, second.checkInsMissed)
        assertEquals(0, second.checkInsCreated)
    }

    // ---- The record ---------------------------------------------------------------------------

    @Test
    fun aSuccessfulRunIsRecordedAndBecomesTheLastSuccess() = runBlocking {
        val repo = repoAt("2026-09-04T04:05")
        assertNull("nothing has run yet", repo.lastSuccessfulRollover())

        repo.runRollover(installDay.plusDays(3))

        assertNotNull(repo.lastSuccessfulRollover())
        assertEquals(1, repo.recentRolloverRuns().size)
    }

    /**
     * A table of successes only cannot tell "it failed every night" from "it never ran", and those
     * need different fixes. So failures are rows too — and they must not masquerade as a success.
     */
    @Test
    fun aFailedRunIsRecordedButDoesNotCountAsTheLastSuccess() = runBlocking {
        val repo = repoAt("2026-09-04T04:05")

        repo.recordRolloverFailure(installDay.plusDays(3), "database locked")

        assertNull("a failure is not a success", repo.lastSuccessfulRollover())
        assertEquals(1, repo.recentRolloverRuns().size)
        assertEquals(ROLLOVER_FAILED, repo.recentRolloverRuns().single().outcome)
    }

    private fun dayResolverAt(local: String) =
        DayResolver(Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone))

    private fun repoAt(local: String) = ConsistencyRepository(db, dayResolverAt(local))
}
