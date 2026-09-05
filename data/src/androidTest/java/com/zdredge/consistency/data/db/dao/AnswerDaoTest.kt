package com.zdredge.consistency.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.Capture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answer queries, stated result-first: each test names the rows it expects back before the query
 * that produces them existed.
 */
@RunWith(AndroidJUnit4::class)
internal class AnswerDaoTest : DaoTestBase() {

    private val answers get() = db.answerDao()

    @Before
    fun seedDefinitions() = dao {
        db.itemDao().insertItems(listOf(Rows.item("meals"), Rows.item("pre_sleep")))
        db.itemDao().insertVersions(
            listOf(
                Rows.version("meals-v1", "meals"),
                Rows.version("pre_sleep-v1", "pre_sleep", answerType = AnswerType.MULTI_SELECT),
            ),
        )
        db.itemDao().insertOptions(
            listOf(
                Rows.option("read_a_book", "pre_sleep", ordinal = 0),
                Rows.option("watched_youtube", "pre_sleep", ordinal = 1),
                Rows.option("scrolled_on_phone", "pre_sleep", ordinal = 2),
            ),
        )
    }

    @Test
    fun rangeReturnsOnlyTheDaysAskedForInclusiveOfBothEnds() = dao {
        insertMeals(day = "2026-08-24", value = 1.0)
        insertMeals(day = "2026-08-25", value = 2.0)
        insertMeals(day = "2026-08-26", value = 3.0)
        insertMeals(day = "2026-08-27", value = 4.0)

        val found = answers.between("2026-08-25", "2026-08-26")

        assertEquals(listOf("2026-08-25", "2026-08-26"), found.map { it.answer.dayDate.toString() })
    }

    /**
     * Dates are stored ISO-8601 precisely so a text BETWEEN sorts chronologically. A month boundary
     * is where a sloppier format would give itself away -- "8/31/2026" and "9/1/2026" compare in the
     * wrong order as text, and the resulting window would quietly drop days rather than fail.
     */
    @Test
    fun aRangeSpanningAMonthBoundaryReturnsTheDaysInChronologicalOrder() = dao {
        insertMeals(day = "2026-08-30", value = 1.0)
        insertMeals(day = "2026-08-31", value = 2.0)
        insertMeals(day = "2026-09-01", value = 3.0)
        insertMeals(day = "2026-09-02", value = 4.0)

        val found = answers.between("2026-08-31", "2026-09-01")

        assertEquals(
            listOf("2026-08-31", "2026-09-01"),
            found.map { it.answer.dayDate.toString() },
        )
    }

    @Test
    fun oneItemsAnswersAcrossARangeExcludeOtherItems() = dao {
        insertMeals(day = "2026-08-25", value = 3.0)
        insertPreSleep(day = "2026-08-25", selections = listOf("read_a_book"))
        insertMeals(day = "2026-08-26", value = 4.0)

        val found = answers.forItemBetween("meals", "2026-08-25", "2026-08-26")

        assertEquals(listOf("meals", "meals"), found.map { it.answer.itemId })
        assertEquals(listOf(3.0, 4.0), found.map { it.answer.valueNumber })
    }

    @Test
    fun anAnswerArrivesWithItsSelectionsAttached() = dao {
        insertPreSleep(day = "2026-08-25", selections = listOf("read_a_book", "watched_youtube"))

        val found = answers.forItemOnDay("pre_sleep", "2026-08-25")

        assertNotNull(found)
        assertEquals(
            setOf("read_a_book", "watched_youtube"),
            found!!.selections.map { it.id }.toSet(),
        )
    }

    /**
     * The distinction the relation exists to protect. An answered multi-select with nothing selected
     * is a row with an empty selection list; an unanswered one is no row at all. Those score
     * differently -- silence is excluded, an empty answer is not (scoring-cases 1.13).
     */
    @Test
    fun anAnsweredMultiSelectWithNothingSelectedIsNotTheSameAsNoAnswer() = dao {
        insertPreSleep(day = "2026-08-25", selections = emptyList())

        val answered = answers.forItemOnDay("pre_sleep", "2026-08-25")
        val silent = answers.forItemOnDay("pre_sleep", "2026-08-26")

        assertNotNull("an answered-but-empty multi-select must still be a row", answered)
        assertTrue(answered!!.selections.isEmpty())
        assertNull("an unanswered day must produce no row at all", silent)
    }

    @Test
    fun replacingAMultiSelectSwapsItsSelectionsWholesale() = dao {
        insertPreSleep(day = "2026-08-25", selections = listOf("read_a_book", "watched_youtube"))

        val existing = answers.forItemOnDay("pre_sleep", "2026-08-25")!!.answer
        answers.replace(existing, listOf(Rows.selection(existing.id, "scrolled_on_phone")))

        val found = answers.forItemOnDay("pre_sleep", "2026-08-25")!!
        assertEquals(listOf("scrolled_on_phone"), found.selections.map { it.id })
    }

    /** The rollover job's list: deferrals awaiting resolution (scoring-cases A2). */
    @Test
    fun pendingAnswersAreFindableByCaptureState() = dao {
        insertMeals(day = "2026-08-25", value = 3.0)
        insertMeals(day = "2026-08-26", value = null, capture = Capture.PENDING)

        val found = answers.withCapture(Capture.PENDING.name)

        assertEquals(listOf("2026-08-26"), found.map { it.answer.dayDate.toString() })
    }

    /**
     * The sleep-day convention in the query layer: an answer submitted through the 26th's morning
     * check-in but belonging to the 25th must be found by asking for the 25th, not the 26th.
     */
    @Test
    fun answersAreFoundByTheDayTheyBelongToNotTheCheckInTheyArrivedThrough() = dao {
        db.checkInDao().insert(listOf(Rows.checkIn("morning-26", day = "2026-08-26")))
        answers.insertAnswer(
            Rows.answer(
                id = "bedtime-25",
                itemId = "meals",
                versionId = "meals-v1",
                day = "2026-08-25",
                viaCheckIn = "morning-26",
                valueNumber = 3.0,
            ),
        )

        assertEquals(1, answers.onDay("2026-08-25").size)
        assertEquals(0, answers.onDay("2026-08-26").size)
    }

    private suspend fun insertMeals(
        day: String,
        value: Double?,
        capture: Capture = Capture.IN_WINDOW,
    ) = answers.insertAnswer(
        Rows.answer(
            id = "meals-$day",
            itemId = "meals",
            versionId = "meals-v1",
            day = day,
            capture = capture,
            valueNumber = value,
        ),
    )

    private suspend fun insertPreSleep(day: String, selections: List<String>) {
        val id = "pre_sleep-$day"
        answers.insertAnswer(
            Rows.answer(id = id, itemId = "pre_sleep", versionId = "pre_sleep-v1", day = day),
        )
        if (selections.isNotEmpty()) {
            answers.insertSelections(selections.map { Rows.selection(id, it) })
        }
    }
}
