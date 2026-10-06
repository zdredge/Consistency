package com.zdredge.consistency.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.data.health.FakeStepSource
import com.zdredge.consistency.data.health.StepSourceStatus
import com.zdredge.consistency.domain.detail.ItemDetails
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.scoring.SleepMetric
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Gathering one item's history out of the database.
 *
 * `ItemHistory` refuses rows belonging to another item, so the dangerous mistake here is not a wrong
 * row — that throws — but a **missing** one. An item read without its options produces a screen with
 * no labels on its answers; without its roll-up spec, a weekly figure that quietly does not exist.
 * Neither fails anywhere, which is why this reads the real database rather than trusting the queries.
 */
@RunWith(AndroidJUnit4::class)
class ItemHistoryRepositoryTest {

    private lateinit var db: ConsistencyDatabase
    private lateinit var repo: ConsistencyRepository

    private val installDay = LocalDate.of(2026, 9, 10)
    private val today = LocalDate.of(2026, 9, 30)
    private val dayResolver = DayResolver(
        Clock.fixed(Instant.parse("2026-09-30T14:00:00Z"), ZoneId.of("America/New_York")),
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
        repo = ConsistencyRepository(db, dayResolver)
        runBlocking { repo.seedLibraryIfEmpty(installDay) }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun anItemArrivesWithItsOptionsTargetsAndRollUp() = runBlocking {
        val history = repo.itemHistory(ItemId("took_time"), today)

        assertNotNull(history)
        // Yes, no, and no opportunity -- the last of which the scorer must be handed, not infer.
        assertEquals(3, history!!.options.size)
        assertEquals(setOf(OptionId("took_time.no_opportunity")), history.noOpportunityOptions)
        assertNotNull(history.targetResolver.resolve(ItemId("took_time"), Period.DAY, today))
    }

    @Test
    fun aRolledUpItemBringsItsAggregationWithIt() = runBlocking {
        // Spec §3.4: roll-ups are declared, never inferred. Without this row the weekly figure is
        // absent rather than wrong, which is the harder kind of missing to notice.
        val workedOut = repo.itemHistory(ItemId("worked_out"), today)!!
        assertEquals(ItemId("worked_out"), workedOut.rollUp?.itemId)

        // And an item that declares none stays without one rather than being given a guess.
        assertNull(repo.itemHistory(ItemId("vitamins"), today)!!.rollUp)
    }

    @Test
    fun onlyThisItemsAnswersComeBack() = runBlocking {
        val day = today.minusDays(1)
        repo.recordAnswer(answer("vitamins", day, bool = true))
        repo.recordAnswer(answer("meals", day, number = 3.0))

        val history = repo.itemHistory(ItemId("vitamins"), today)!!

        // ItemHistory would have thrown on a stray row; this asserts the query is not merely lucky.
        assertEquals(1, history.answers.size)
        assertEquals(ItemId("vitamins"), history.answers.single().itemId)
    }

    @Test
    fun measuredValuesReachTheMeasuredItemAndNoOther() = runBlocking {
        repo.recordMeasuredValue(
            MeasuredValue(
                itemId = ItemId(SeedLibrary.STEPS),
                day = today.minusDays(1),
                value = 9_100.0,
                state = MeasuredState.FROZEN,
            ),
        )

        val steps = repo.itemHistory(ItemId(SeedLibrary.STEPS), today)!!
        assertEquals(ItemKind.MEASURED, steps.item.kind)
        assertEquals(1, steps.measured.size)

        // An asked item is not handed the step rows, and is not asked the question either.
        assertTrue(repo.itemHistory(ItemId("meals"), today)!!.measured.isEmpty())
    }

    @Test
    fun aDeferralComesBackAsADeferralRatherThanAnAnswer() = runBlocking {
        val day = today.minusDays(1)
        repo.recordAnswer(answer("meals", day, number = null, capture = Capture.PENDING))

        val history = repo.itemHistory(ItemId("meals"), today)!!

        assertEquals(Capture.PENDING, history.answers.single().capture)
        // A2.1 in full: still resolvable this morning, so the day is not yet a miss.
        val detail = ItemDetails.assemble(history, today, dayResolver)
        assertEquals(
            com.zdredge.consistency.domain.detail.DayState.DEFERRED,
            detail.days.single { it.day == day }.state,
        )
    }

    @Test
    fun theSleepItemsArriveWithTheThreeTimesTheirMetricIsComputedFrom() = runBlocking {
        val night = today.minusDays(2)
        repo.recordAnswer(answer(SeedLibrary.BEDTIME, night, time = LocalTime.of(23, 30)))
        repo.recordAnswer(answer(SeedLibrary.WOKE_AT, night, time = LocalTime.of(8, 30)))
        repo.recordAnswer(answer(SeedLibrary.GOT_UP_AT, night, time = LocalTime.of(8, 52)))

        // Constraint 13: the two metrics are wired to these three items, read from their own rows. A
        // missing one would not throw -- the figure would simply be absent, case 8.4's "unavailable".
        val woke = repo.itemHistory(ItemId(SeedLibrary.WOKE_AT), today)!!
        assertEquals(SleepMetric.SLEEP_DURATION, woke.sleepMetric)
        assertEquals(Duration.ofHours(9), woke.sleep!!.on(night).sleepDuration)

        val gotUp = repo.itemHistory(ItemId(SeedLibrary.GOT_UP_AT), today)!!
        assertEquals(SleepMetric.LINGERING, gotUp.sleepMetric)
        assertEquals(Duration.ofMinutes(22), gotUp.sleep!!.on(night).lingering)

        // Bedtime shows neither, and is not handed the other items' times.
        assertNull(repo.itemHistory(ItemId(SeedLibrary.BEDTIME), today)!!.sleep)
    }

    @Test
    fun waterArrivesWithItsBottleSize() = runBlocking {
        // Without this the absolute amount is not wrong but missing -- "2" with no "80 oz" beside it.
        val water = repo.itemHistory(ItemId("water"), today)!!
        assertEquals(40.0, water.containerResolver.resolve(ItemId("water"), today)!!.size, 0.0)
        assertTrue(repo.itemHistory(ItemId("meals"), today)!!.containerSizes.isEmpty())
    }

    @Test
    fun stepsIsListedOnlyWhileStepsCanBeRead() = runBlocking {
        // Spec §3.3: declined health permission hides steps -- from the list as well as the check-in.
        // The default source here is unavailable, which is the declined case.
        val steps = ItemId(SeedLibrary.STEPS)
        assertTrue(repo.listedItems().none { it.id == steps })
        assertEquals(repo.items().size - 1, repo.listedItems().size)

        val missing = ConsistencyRepository(db, dayResolver, stepSource = FakeStepSource(StepSourceStatus.PermissionMissing))
        assertTrue(missing.listedItems().none { it.id == steps })

        // Granted again: it comes back, history and all -- hidden is not deleted.
        val granted = ConsistencyRepository(db, dayResolver, stepSource = FakeStepSource(StepSourceStatus.Available))
        assertTrue(granted.listedItems().any { it.id == steps })
    }

    @Test
    fun anItemThatIsNotThereIsNullRatherThanEmpty() = runBlocking {
        // Reachable through a back stack that outlived the item. Null lets the screen say so; an
        // empty history would draw a real item with nothing in it.
        assertNull(repo.itemHistory(ItemId("not_an_item"), today))
    }

    @Test
    fun theWholeLibraryAssemblesFromTheRealRows() = runBlocking {
        // The end-to-end shape: every seeded item, read from the database and put through the real
        // assembly. Nothing here should be a missed day -- the library is three weeks old with no
        // answers in it, and silence is not failure.
        for (item in repo.items()) {
            val history = repo.itemHistory(item.id, today)
            assertNotNull("${item.id.value} has no history", history)

            val detail = ItemDetails.assemble(history!!, today, dayResolver)
            assertEquals(
                "${item.id.value} shows a missed day with nothing ever answered",
                0,
                detail.days.count { it.state == com.zdredge.consistency.domain.detail.DayState.MISSED },
            )
            assertEquals(14, detail.figures.windowDays.size)
        }
    }

    private fun answer(
        itemId: String,
        day: LocalDate,
        number: Double? = null,
        bool: Boolean? = null,
        time: LocalTime? = null,
        capture: Capture = Capture.IN_WINDOW,
    ) = Answer(
        itemId = ItemId(itemId),
        itemVersionId = ItemVersionId("$itemId.v1"),
        day = day,
        capture = capture,
        submittedAt = dayResolver.now(),
        valueBool = bool,
        valueNumber = number,
        valueTime = time,
    )
}
