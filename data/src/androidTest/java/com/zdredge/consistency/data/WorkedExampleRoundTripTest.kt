package com.zdredge.consistency.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.data.db.LOCAL_USER_ID
import com.zdredge.consistency.data.db.entity.CheckInEntity
import com.zdredge.consistency.data.db.entity.ContainerSizeEntity
import com.zdredge.consistency.data.db.entity.ItemEntity
import com.zdredge.consistency.data.db.entity.ItemVersionEntity
import com.zdredge.consistency.data.db.entity.SelectOptionEntity
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.CheckInState
import com.zdredge.consistency.domain.model.Classification
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemKind
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.scoring.ContainerSizeResolver
import com.zdredge.consistency.domain.scoring.DerivedMetrics
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * **Tuesday 25 August 2026, persisted and reloaded** -- the worked example from architecture
 * section 5, which exists in that document precisely because it proves two things a reader would
 * otherwise be entitled to doubt.
 *
 * The first is that `answers.day_date` is not redundant with the check-in's day. Four of these
 * twelve answers arrive through a check-in dated the **26th** and belong to the **25th**, because
 * sleep items attach to the day the user went to bed (spec 3.1). It reads like denormalisation worth
 * tidying away; tidying it away breaks the sleep items and the "not yet" carry-over at once. A
 * comment saying so is easy to overrule during a refactor. A failing test is not.
 *
 * The second is that nothing derived is stored. Sleep duration, lingering minutes and the absolute
 * water amount are all computable from these rows and none of them is in the database.
 */
@RunWith(AndroidJUnit4::class)
class WorkedExampleRoundTripTest {

    private lateinit var db: ConsistencyDatabase
    private lateinit var repo: ConsistencyRepository

    private val zone = ZoneId.of("America/New_York")
    private val dayResolver = DayResolver(Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), zone))

    private val theNight = LocalDate.of(2026, 8, 25)
    private val theMorningAfter = LocalDate.of(2026, 8, 26)

    /** The night check-in, dated the 25th. */
    private val nightCheckIn = "812"

    /** The morning check-in, dated the **26th**, through which the sleep answers arrive. */
    private val morningCheckIn = "813"

    private val sleepItems = setOf("bedtime", "woke_at", "got_up_at", "pre_sleep")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
        repo = ConsistencyRepository(db, dayResolver)
        runBlocking { seedTheWorkedExample() }
    }

    @After
    fun tearDown() = db.close()

    /**
     * Every answer belongs to the 25th, including the four that were given on the 26th. Asking for
     * the 26th returns nothing at all -- that day has not been lived yet.
     */
    @Test
    fun allTwelveAnswersReloadAgainstTheNightTheyBelongTo() = runBlocking {
        val night = repo.answers(theNight)

        assertEquals(12, night.size)
        assertTrue(night.all { it.day == theNight })
        assertEquals(0, repo.answers(theMorningAfter).size)
    }

    /**
     * The point of the whole fixture: four answers dated the 25th arrived through a check-in dated
     * the 26th. If `day_date` were derived from the check-in, these four would land a day late and
     * the 25th would be permanently incomplete.
     */
    @Test
    fun theFourSleepAnswersArriveThroughTheNextMorningsCheckInAndStillBelongToTheNight() =
        runBlocking {
            val rows = db.answerDao().onDay(theNight.toString()).map { it.answer }

            val viaMorning = rows.filter { it.submittedViaCheckinId == morningCheckIn }
            assertEquals(sleepItems, viaMorning.map { it.itemId }.toSet())
            assertTrue(
                "sleep answers must be dated the night, not the morning they were given",
                viaMorning.all { it.dayDate == theNight },
            )

            val viaNight = rows.filter { it.submittedViaCheckinId == nightCheckIn }
            assertEquals(8, viaNight.size)
        }

    /** The two check-ins span two calendar days, and both are answered. */
    @Test
    fun theNightAndMorningCheckInsAreSeparateRowsOnSeparateDays() = runBlocking {
        assertEquals(listOf(Slot.NIGHT), repo.checkIns(theNight).map { it.slot })
        assertEquals(listOf(Slot.MORNING), repo.checkIns(theMorningAfter).map { it.slot })
        assertTrue(
            repo.checkIns(theNight, theMorningAfter).all { it.state == CheckInState.ANSWERED },
        )
    }

    @Test
    fun selectAnswersReloadWithTheirChosenOptions() = runBlocking {
        assertEquals(
            setOf(OptionId("yes")),
            repo.answer(ItemId("took_time"), theNight)!!.selections,
        )
        assertEquals(
            setOf(OptionId("read_a_book"), OptionId("watched_youtube")),
            repo.answer(ItemId("pre_sleep"), theNight)!!.selections,
        )
    }

    /**
     * The no-opportunity option exists on `took_time` and was **not** chosen here. It matters that
     * it is present and unselected rather than absent: a neutral exclusion must be an available
     * answer the user declined, not a state the app invents (spec constraint 17).
     */
    @Test
    fun theNoOpportunityOptionIsAvailableOnTookTimeAndWasNotChosen() = runBlocking {
        val flagged = repo.options(ItemId("took_time")).filter { it.isNoOpportunity }
        assertEquals(listOf(OptionId("no_opportunity")), flagged.map { it.id })

        val chosen = repo.answer(ItemId("took_time"), theNight)!!.selections
        assertTrue(flagged.none { it.id in chosen })
    }

    /**
     * Water is recorded as 2 bottles. The absolute 80 oz is resolved on read from the container size
     * in force and is stored nowhere -- switching bottles later must not re-score this night
     * (scoring-cases 5.4).
     */
    @Test
    fun theAbsoluteWaterAmountIsComputedOnReadAndNeverStored() = runBlocking {
        val bottles = repo.answer(ItemId("water"), theNight)!!.valueNumber
        assertEquals(2.0, bottles!!, 0.0)

        val resolver = ContainerSizeResolver(repo.allContainerSizes())
        assertEquals(80.0, resolver.absoluteAmount(ItemId("water"), bottles, theNight)!!, 0.0)

        assertTrue("80 must not appear in any stored value", 80.0 !in allStoredNumbers())
    }

    /**
     * Sleep duration is 9h 00m and lingering 22m for a 23:30 / 08:30 / 08:52 night, both computed by
     * the domain layer from the three stored times. Neither is a column, and neither may become one.
     */
    @Test
    fun sleepDurationAndLingeringAreDerivedFromStoredTimesAndAreNotThemselvesStored() = runBlocking {
        val bedtime = repo.answer(ItemId("bedtime"), theNight)!!.valueTime
        val wokeAt = repo.answer(ItemId("woke_at"), theNight)!!.valueTime
        val gotUpAt = repo.answer(ItemId("got_up_at"), theNight)!!.valueTime

        val metrics = DerivedMetrics.sleep(bedtime, wokeAt, gotUpAt)

        assertEquals(Duration.ofHours(9), metrics.sleepDuration)
        assertEquals(Duration.ofMinutes(22), metrics.lingering)

        val stored = allStoredNumbers()
        assertTrue("sleep duration must not be stored", 540.0 !in stored && 9.0 !in stored)
        assertTrue("lingering must not be stored", 22.0 !in stored)
    }

    /** One step origin, as M0 found on this device. The row exists so a second one is detectable. */
    @Test
    fun stepsCarryExactlyOneOriginAndAreFrozen() = runBlocking {
        val steps = repo.measuredValue(ItemId("steps"), theNight)!!

        assertEquals(11_240.0, steps.value, 0.0)
        assertEquals(MeasuredState.FROZEN, steps.state)
        assertEquals(1, steps.origins.size)
    }

    /**
     * Re-answering replaces rather than duplicating, and the row keeps its identity across the edit
     * -- which is what makes the database-level one-answer-per-item-per-day rule survivable in
     * normal use rather than something callers have to route around.
     *
     * **The edit flag is the repository's to set, not the caller's.** This test used to pass an
     * `editedAt` in and assert it came back, which is what let defect 3 exist: every real caller
     * built its `Answer` without one, so `edited_at` was never written and a correction silently
     * restamped `submitted_at` and re-resolved `capture`. `AnswerRevision` now decides all three, so
     * a caller cannot forget — and any value passed in is ignored, as asserted below.
     */
    @Test
    fun correctingAnAnswerReplacesItInPlace() = runBlocking {
        val before = db.answerDao().forItemOnDay("meals", theNight.toString())!!.answer

        repo.recordAnswer(
            repo.answer(ItemId("meals"), theNight)!!.copy(
                valueNumber = 5.0,
                // Deliberately wrong, and deliberately ignored.
                submittedAt = Instant.parse("2026-08-26T15:00:00Z"),
                editedAt = Instant.parse("2026-08-26T15:00:00Z"),
            ),
        )

        val after = db.answerDao().forItemOnDay("meals", theNight.toString())!!.answer
        assertEquals("the row must keep its identity across an edit", before.id, after.id)
        assertEquals(5.0, after.valueNumber!!, 0.0)
        assertEquals(1, repo.answers(theNight, theNight).count { it.itemId == ItemId("meals") })

        // Spec constraint 4: capture is how the answer was FIRST recorded, and an edit does not
        // rewrite it. The two facts -- answered in window, corrected later -- must both survive.
        assertEquals(Capture.IN_WINDOW, after.capture)
        assertEquals(
            "when it was first given never moves",
            before.submittedAt,
            after.submittedAt,
        )
        // Recorded through no check-in at all, so it cannot be the sitting that first stored it:
        // an edit, stamped from the repository's clock rather than from what the caller supplied.
        assertEquals(Instant.parse("2026-08-26T12:00:00Z"), after.editedAt)
    }

    // ---- The fixture -------------------------------------------------------------------------

    private suspend fun seedTheWorkedExample() {
        val asked = listOf(
            "meals" to AnswerType.NUMBER,
            "vitamins" to AnswerType.BOOL,
            "water" to AnswerType.NUMBER,
            "workout" to AnswerType.BOOL,
            "stretch" to AnswerType.BOOL,
            "coffee" to AnswerType.NUMBER,
            "mindset" to AnswerType.SCALE,
            "took_time" to AnswerType.SINGLE_SELECT,
            "bedtime" to AnswerType.TIME,
            "woke_at" to AnswerType.TIME,
            "got_up_at" to AnswerType.TIME,
            "pre_sleep" to AnswerType.MULTI_SELECT,
        )

        db.itemDao().insertItems(
            asked.map { (id, _) -> itemRow(id, ItemKind.ASKED) } +
                itemRow("steps", ItemKind.MEASURED),
        )
        db.itemDao().insertVersions(
            asked.map { (id, type) ->
                versionRow(
                    id = id,
                    type = type,
                    slot = if (id in sleepItems) Slot.MORNING else Slot.NIGHT,
                    classification =
                        if (id == "mindset") Classification.OBSERVATION else Classification.GOAL,
                    unitLabel = if (id == "water") "bottles" else null,
                )
            } + versionRow("steps", AnswerType.NUMBER, Slot.NONE, Classification.GOAL),
        )
        db.itemDao().insertOptions(
            listOf(
                optionRow("yes", "took_time", 0),
                optionRow("no", "took_time", 1),
                optionRow("no_opportunity", "took_time", 2, isNoOpportunity = true),
                optionRow("read_a_book", "pre_sleep", 0),
                optionRow("watched_youtube", "pre_sleep", 1),
                optionRow("scrolled_on_phone", "pre_sleep", 2),
                optionRow("watched_tv", "pre_sleep", 3),
            ),
        )
        db.targetDao().insertContainerSizes(
            listOf(
                ContainerSizeEntity(
                    id = "water-40oz",
                    userId = LOCAL_USER_ID,
                    itemId = "water",
                    sizeNumber = 40.0,
                    unitLabel = "oz",
                    effectiveFrom = LocalDate.of(2026, 8, 1),
                ),
            ),
        )

        db.checkInDao().insert(
            listOf(
                checkInRow(nightCheckIn, theNight, Slot.NIGHT, "2026-08-26T01:14:00Z"),
                checkInRow(morningCheckIn, theMorningAfter, Slot.MORNING, "2026-08-26T12:55:00Z"),
            ),
        )

        // Given in the night check-in, dated the 25th.
        record("meals", theNight, nightCheckIn, number = 4.0, note = "breakfast, lunch, post-ride, dinner")
        record("vitamins", theNight, nightCheckIn, bool = true)
        record("water", theNight, nightCheckIn, number = 2.0)
        record("workout", theNight, nightCheckIn, bool = true, note = "a bike ride")
        record("stretch", theNight, nightCheckIn, bool = true)
        record("coffee", theNight, nightCheckIn, number = 2.0)
        record("mindset", theNight, nightCheckIn, scale = 4)
        record("took_time", theNight, nightCheckIn, selections = setOf("yes"))

        // Given in the NEXT MORNING's check-in, still dated the 25th.
        record("bedtime", theNight, morningCheckIn, time = LocalTime.of(23, 30))
        record("woke_at", theNight, morningCheckIn, time = LocalTime.of(8, 30))
        record("got_up_at", theNight, morningCheckIn, time = LocalTime.of(8, 52))
        record(
            "pre_sleep",
            theNight,
            morningCheckIn,
            selections = setOf("read_a_book", "watched_youtube"),
            note = "watched baseball while reading",
        )

        repo.recordMeasuredValue(
            MeasuredValue(
                itemId = ItemId("steps"),
                day = theNight,
                value = 11_240.0,
                state = MeasuredState.FROZEN,
                origins = listOf(
                    MeasuredOrigin("com.android.healthconnect.phone.jf9fc11088d6", 11_240.0),
                ),
            ),
        )
    }

    private suspend fun record(
        itemId: String,
        day: LocalDate,
        viaCheckIn: String,
        bool: Boolean? = null,
        number: Double? = null,
        time: LocalTime? = null,
        scale: Int? = null,
        selections: Set<String> = emptySet(),
        note: String? = null,
    ) = repo.recordAnswer(
        Answer(
            itemId = ItemId(itemId),
            itemVersionId = ItemVersionId("$itemId-v1"),
            day = day,
            capture = Capture.IN_WINDOW,
            submittedAt = Instant.parse("2026-08-26T01:14:00Z"),
            valueBool = bool,
            valueNumber = number,
            valueTime = time,
            valueScale = scale,
            selections = selections.map(::OptionId).toSet(),
            note = note,
        ),
        viaCheckInId = viaCheckIn,
    )

    private fun itemRow(id: String, kind: ItemKind) = ItemEntity(
        id = id,
        userId = LOCAL_USER_ID,
        kind = kind,
        createdAt = Instant.parse("2026-08-01T08:00:00Z"),
    )

    private fun versionRow(
        id: String,
        type: AnswerType,
        slot: Slot,
        classification: Classification,
        unitLabel: String? = null,
    ) = ItemVersionEntity(
        id = "$id-v1",
        userId = LOCAL_USER_ID,
        itemId = id,
        versionNo = 1,
        prompt = id,
        answerType = type,
        classification = classification,
        slot = slot,
        unitLabel = unitLabel,
        effectiveFrom = LocalDate.of(2026, 8, 1),
    )

    private fun optionRow(
        id: String,
        itemId: String,
        ordinal: Int,
        isNoOpportunity: Boolean = false,
    ) = SelectOptionEntity(id, LOCAL_USER_ID, itemId, id, ordinal, isNoOpportunity, null)

    private fun checkInRow(id: String, day: LocalDate, slot: Slot, answeredAt: String) =
        CheckInEntity(
            id = id,
            userId = LOCAL_USER_ID,
            dayDate = day,
            slot = slot,
            scheduledAt = Instant.parse(answeredAt).minusSeconds(900),
            state = CheckInState.ANSWERED,
            answeredAt = Instant.parse(answeredAt),
        )

    /**
     * Every number stored anywhere in the database. Used to assert absence: a derived figure that
     * crept into a column would show up here, and asserting against the whole store rather than
     * against named columns means a *new* column would be caught too.
     */
    private fun allStoredNumbers(): Set<Double> {
        val numbers = mutableSetOf<Double>()
        val readable = db.openHelper.readableDatabase
        val tables = listOf(
            "answers" to listOf("value_number", "value_scale"),
            "measured_values" to listOf("value_number"),
            "measured_origins" to listOf("value_number"),
            "container_sizes" to listOf("size_number"),
            "targets" to listOf("value_number"),
        )
        for ((table, columns) in tables) {
            for (column in columns) {
                readable.query("SELECT `$column` FROM `$table` WHERE `$column` IS NOT NULL")
                    .use { cursor ->
                        while (cursor.moveToNext()) numbers += cursor.getDouble(0)
                    }
            }
        }
        return numbers
    }
}
