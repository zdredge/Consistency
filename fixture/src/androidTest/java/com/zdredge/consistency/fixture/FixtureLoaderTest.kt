package com.zdredge.consistency.fixture

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A scenario survives storage: what is loaded is what the app reads back.
 *
 * The JVM tests prove each scenario *holds* its state as domain models. This proves the state reaches
 * the screens -- through the entity mappers, the unique indexes and the type converters, read back
 * through `ConsistencyRepository`, which is the only way the app itself reads anything.
 */
@RunWith(AndroidJUnit4::class)
class FixtureLoaderTest {

    private val zone = ZoneId.of("America/New_York")
    private val clock = Clock.fixed(LocalDateTime.parse("2026-09-13T16:00").atZone(zone).toInstant(), zone)
    private val dayResolver = DayResolver(clock)
    private val today = dayResolver.today()

    private lateinit var db: ConsistencyDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun everyScenarioReadsBackAsItWasGenerated() = runBlocking {
        Scenarios.all.forEach { scenario ->
            val dataset = scenario.generate(clock)
            FixtureLoader(db, dayResolver).load(dataset)
            val repo = ConsistencyRepository(db, dayResolver)
            val from = dataset.items.minOf { it.createdOn }
            val id = scenario.id

            assertEquals("$id items", dataset.items.toSet(), repo.items().toSet())
            assertEquals("$id versions", dataset.versions.toSet(), repo.versions().toSet())
            assertEquals("$id options", dataset.options.toSet(), repo.allOptions().toSet())
            assertEquals("$id targets", dataset.targets.toSet(), repo.allTargets().toSet())
            assertEquals("$id containers", dataset.containerSizes.toSet(), repo.allContainerSizes().toSet())
            assertEquals("$id roll-ups", dataset.rollUpSpecs.toSet(), repo.rollUpSpecs().toSet())
            assertEquals("$id check-ins", dataset.checkIns.toSet(), repo.checkIns(from, today).toSet())
            assertEquals("$id answers", dataset.answers.map { it.answer }.toSet(), repo.answers(from, today).toSet())
            assertEquals("$id steps", dataset.measuredValues.toSet(), repo.measuredValues(from, today).toSet())

            // Every item's screen can be built, retired ones included.
            dataset.items.forEach { assertNotNull("$id ${it.id}", repo.itemHistory(it.id, today)) }
        }
    }

    /**
     * The app's own jobs find nothing to do on a loaded scenario: nothing to generate for today, and
     * nothing waiting to be marked missed or frozen. A fixture the rollover would "correct" on its
     * first run is a fixture holding a state the app never has.
     *
     * Generation only looks forward from the latest stored day, so this does not prove the history
     * has every check-in -- `HistoryGeneratorTest.everyExpectedCheckInExistsExactlyOnce` does.
     */
    @Test
    fun theAppFindsNothingToRepairAfterALoad() = runBlocking {
        Scenarios.all.forEach { scenario ->
            FixtureLoader(db, dayResolver).load(scenario.generate(clock))
            val repo = ConsistencyRepository(db, dayResolver)

            assertEquals("${scenario.id} check-ins created", 0, repo.ensureCheckInsExist(today))
            val outcome = repo.runRollover(today)
            assertEquals("${scenario.id} missed by rollover", 0, outcome.checkInsMissed)
            assertEquals("${scenario.id} frozen by rollover", 0, outcome.valuesFrozen)
        }
    }

    /** A load replaces; it never merges. Loading twice, or a smaller scenario over a larger one, leaves only the last. */
    @Test
    fun loadingReplacesWhateverWasThere() = runBlocking {
        val loader = FixtureLoader(db, dayResolver)
        loader.load(Scenarios.SixMonths.generate(clock))
        loader.load(Scenarios.SixMonths.generate(clock))
        val small = Scenarios.Day13.generate(clock)
        loader.load(small)

        val repo = ConsistencyRepository(db, dayResolver)
        val longAgo = today.minusYears(1)
        assertEquals(small.checkIns.size, repo.checkIns(longAgo, today).size)
        assertEquals(small.answers.size, repo.answers(longAgo, today).size)
        assertEquals(small.measuredValues.size, repo.measuredValues(longAgo, today).size)
        assertEquals(1, repo.recentRolloverRuns().size)
        assertEquals(ItemKind.values().size, repo.items().map { it.kind }.toSet().size)
    }
}
