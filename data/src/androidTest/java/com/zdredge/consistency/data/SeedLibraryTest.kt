package com.zdredge.consistency.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.Direction
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Period
import com.zdredge.consistency.domain.model.RollUpAggregation
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.scoring.ContainerSizeResolver
import com.zdredge.consistency.domain.scoring.TargetResolver
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The seed library, asserted against spec section 4 rather than against itself.
 *
 * These tests are worth having because the library is where a *product* error hides most easily. It
 * compiles and runs whatever targets it is given, and a wrong direction produces a plausible number
 * rather than a failure -- which is how "exactly 3 meals" and a weekly-only coffee cap both survived
 * review. Each assertion below names the spec rule it is holding.
 */
@RunWith(AndroidJUnit4::class)
class SeedLibraryTest {

    private lateinit var db: ConsistencyDatabase
    private lateinit var repo: ConsistencyRepository

    private val installDay = LocalDate.of(2026, 9, 5)
    private val dayResolver = DayResolver(
        Clock.fixed(Instant.parse("2026-09-05T14:00:00Z"), ZoneId.of("America/New_York")),
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
    fun theLibraryHoldsTheItemsSpecSection4Lists() = runBlocking {
        val ids = repo.items().map { it.id.value }.toSet()

        assertEquals(
            setOf(
                "bedtime", "pre_sleep", "woke_at", "got_up_at",
                "meals", "vitamins", "water", "worked_out", "stretched", "coffee",
                "took_time", "mindset",
                "steps",
                "saw_friends", "did_something_fun", "invited_someone",
            ),
            ids,
        )
    }

    /** Spec 3.3: measured items have no schedule slot and are never asked. */
    @Test
    fun stepsIsTheOnlyMeasuredItemAndHasNoSlot() = runBlocking {
        val measured = repo.items().filter { it.kind == ItemKind.MEASURED }
        assertEquals(listOf(ItemId("steps")), measured.map { it.id })
        assertEquals(Slot.NONE, repo.versions(ItemId("steps")).single().slot)
    }

    @Test
    fun itemsAreAssignedTheSlotsSpecSection4GivesThem() = runBlocking {
        val slots = repo.versions().associate { it.itemId.value to it.slot }

        assertEquals(Slot.MORNING, slots.getValue("bedtime"))
        assertEquals(Slot.MORNING, slots.getValue("pre_sleep"))
        assertEquals(Slot.MORNING, slots.getValue("woke_at"))
        assertEquals(Slot.MORNING, slots.getValue("got_up_at"))
        assertEquals(Slot.NIGHT, slots.getValue("meals"))
        assertEquals(Slot.NIGHT, slots.getValue("took_time"))
        assertEquals(Slot.WEEKLY, slots.getValue("saw_friends"))
        assertEquals(Slot.WEEKLY, slots.getValue("did_something_fun"))
        assertEquals(Slot.WEEKLY, slots.getValue("invited_someone"))
    }

    /**
     * Spec section 4: mindset is an observation, not a goal. Scoring a mood reintroduces exactly the
     * shame the product is designed against, and it would drag goal completion down on low days.
     */
    @Test
    fun mindsetIsAnObservationAndCarriesNoTarget() = runBlocking {
        val mindset = repo.versions(ItemId("mindset")).single()
        assertEquals(Classification.OBSERVATION, mindset.classification)
        assertEquals(AnswerType.SCALE, mindset.answerType)
        assertTrue(repo.targets(ItemId("mindset")).isEmpty())
    }

    /** The three sleep times are recorded, never scored; they exist to feed the derived pair. */
    @Test
    fun theSleepTimesAreObservationsWithNoTargets() = runBlocking {
        for (id in listOf("bedtime", "woke_at", "got_up_at")) {
            val version = repo.versions(ItemId(id)).single()
            assertEquals(id, Classification.OBSERVATION, version.classification)
            assertEquals(id, AnswerType.TIME, version.answerType)
            assertTrue(id, repo.targets(ItemId(id)).isEmpty())
        }
    }

    /**
     * Spec section 4, decided during M2: at least three, not exactly three. Under an exact target a
     * fourth meal scores as a miss with attainment capped at 100%, so the dashboard would read
     * "missed, 100%". The direction was wrong, not the arithmetic.
     */
    @Test
    fun mealsIsAtLeastThreeAndNoSeedGoalUsesExactly() = runBlocking {
        val meals = repo.targets(ItemId("meals"), Period.DAY).single()
        assertEquals(Direction.AT_LEAST, meals.direction)
        assertEquals(3.0, meals.valueNumber!!, 0.0)

        assertTrue(
            "no seed goal may use EXACTLY",
            repo.allTargets().none { it.direction == Direction.EXACTLY },
        )
    }

    /**
     * **The M3 product finding.** Coffee is an upper bound, and weekly granularity forgives
     * clustering: five coffees in one day and one on each of the others sums to ten and would pass a
     * weekly cap of fourteen cleanly. So it carries both, scored independently (spec 3.4,
     * scoring-cases 7.1-7.2).
     */
    @Test
    fun coffeeIsCappedDailyAsWellAsWeeklyBecauseAWeeklyCapAloneForgivesASpike() = runBlocking {
        val daily = repo.targets(ItemId("coffee"), Period.DAY).single()
        val weekly = repo.targets(ItemId("coffee"), Period.WEEK).single()

        assertEquals(Direction.AT_MOST, daily.direction)
        assertEquals(2.0, daily.valueNumber!!, 0.0)
        assertEquals(Direction.AT_MOST, weekly.direction)
        assertEquals(14.0, weekly.valueNumber!!, 0.0)

        // The spike the daily cap exists to catch: five in a day inside a week summing to ten.
        val resolver = TargetResolver(repo.targets(ItemId("coffee")))
        val onDay = resolver.resolve(ItemId("coffee"), Period.DAY, installDay)!!
        assertTrue("five coffees must fail the daily cap", 5.0 > onDay.valueNumber!!)
    }

    /**
     * Worked out and stretched are lower bounds on inherently non-daily behaviours, so weekly-only
     * is correct: a daily "must be yes" would read as a ~50% hit rate for no real failure. The
     * clustering problem that gave coffee a daily cap does not apply to a lower bound.
     */
    @Test
    fun workedOutAndStretchedCarryWeeklyTargetsOnly() = runBlocking {
        for (id in listOf("worked_out", "stretched")) {
            assertTrue(id, repo.targets(ItemId(id), Period.DAY).isEmpty())
            assertEquals(id, Direction.AT_LEAST, repo.targets(ItemId(id), Period.WEEK).single().direction)
        }
        assertEquals(3.0, repo.targets(ItemId("worked_out"), Period.WEEK).single().valueNumber!!, 0.0)
        assertEquals(4.0, repo.targets(ItemId("stretched"), Period.WEEK).single().valueNumber!!, 0.0)
    }

    /** Roll-ups are explicit, not inferred: nothing guesses count-of-yes versus sum. */
    @Test
    fun theWeeklyRollUpsDeclareTheirAggregationRatherThanLeavingItToBeGuessed() = runBlocking {
        val byItem = repo.rollUpSpecs().associate { it.itemId.value to it.aggregation }

        assertEquals(RollUpAggregation.COUNT_OF_YES, byItem.getValue("worked_out"))
        assertEquals(RollUpAggregation.COUNT_OF_YES, byItem.getValue("stretched"))
        assertEquals(RollUpAggregation.SUM, byItem.getValue("coffee"))
        assertEquals(RollUpAggregation.AVERAGE, byItem.getValue("mindset"))
    }

    /**
     * Spec 3.4: the pre-sleep goal is "did not scroll", not "read specifically". Expressing several
     * acceptable activities as an absence rule on the unacceptable one is what lets YouTube and TV
     * both pass -- a must-include naming one option would fail an evening of YouTube.
     */
    @Test
    fun preSleepIsAnAbsenceRuleOnScrollingRatherThanAMustIncludeOnReading() = runBlocking {
        val target = repo.targets(ItemId("pre_sleep"), Period.DAY).single()

        assertEquals(Direction.MUST_NOT_INCLUDE, target.direction)
        assertEquals(OptionId("pre_sleep.scrolled_on_phone"), target.optionId)

        val options = repo.options(ItemId("pre_sleep")).map { it.id.value }
        assertTrue(options.containsAll(listOf("pre_sleep.read_a_book", "pre_sleep.watched_youtube")))
    }

    /**
     * Spec constraint 17: exactly the four goals that need a neutral answer carry one, and it is a
     * flag on the option rather than a new answer state.
     */
    @Test
    fun exactlyFourGoalsCarryANoOpportunityOption() = runBlocking {
        val carrying = repo.allOptions().filter { it.isNoOpportunity }.map { it.itemId.value }.toSet()

        assertEquals(
            setOf("took_time", "saw_friends", "did_something_fun", "invited_someone"),
            carrying,
        )
    }

    /** Each of those four is a must-be-yes goal; the neutral option is evaluated before direction. */
    @Test
    fun theSocialGoalsAndTookTimeAreScoredMustBeYes() = runBlocking {
        assertEquals(
            Direction.MUST_INCLUDE,
            repo.targets(ItemId("took_time"), Period.DAY).single().direction,
        )
        for (id in listOf("saw_friends", "did_something_fun", "invited_someone")) {
            val target = repo.targets(ItemId(id), Period.WEEK).single()
            assertEquals(id, Direction.MUST_INCLUDE, target.direction)
            assertEquals(id, OptionId("$id.yes"), target.optionId)
        }
    }

    /** Water is counted in bottles; the absolute amount resolves on read and is never stored. */
    @Test
    fun waterIsCountedInBottlesOfAKnownSize() = runBlocking {
        assertEquals("bottles", repo.versions(ItemId("water")).single().unitLabel)

        val resolver = ContainerSizeResolver(repo.allContainerSizes())
        assertEquals(80.0, resolver.absoluteAmount(ItemId("water"), 2.0, installDay)!!, 0.0)
    }

    /**
     * Everything takes effect from the install day, so no period before installation is scored and
     * a target raised later cannot reach back (spec constraint 2).
     */
    @Test
    fun nothingIsInForceBeforeTheDayTheLibraryWasSeeded() = runBlocking {
        val resolver = TargetResolver(repo.allTargets())

        assertNull(resolver.resolve(ItemId("meals"), Period.DAY, installDay.minusDays(1)))
        assertEquals(3.0, resolver.resolve(ItemId("meals"), Period.DAY, installDay)!!.valueNumber!!, 0.0)
    }

    /**
     * The library is editable and removable from the moment it lands (spec section 4), so re-running
     * must not resurrect what the user deleted. Guarding on emptiness rather than on a stored flag
     * means the guard cannot disagree with the rows it is guarding.
     */
    @Test
    fun seedingIsSkippedOnADatabaseThatAlreadyHasItems() = runBlocking {
        val countBefore = repo.items().size

        val seededAgain = repo.seedLibraryIfEmpty(installDay.plusDays(30))

        assertFalse("a second seed must be refused", seededAgain)
        assertEquals(countBefore, repo.items().size)
    }
}
