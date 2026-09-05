package com.zdredge.consistency.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.domain.model.AnswerType
import com.zdredge.consistency.domain.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
internal class ItemDaoTest : DaoTestBase() {

    private val items get() = db.itemDao()

    /**
     * A retired item stays visible for the periods in which it was active (spec 3.3), so the query
     * must return it. Whether it was active on a given date is :domain's question, and filtering it
     * out in SQL would erase history rather than tidy a list.
     */
    @Test
    fun retiredItemsAreStillReturned() = dao {
        items.insertItems(
            listOf(
                Rows.item("meals"),
                Rows.item("old_habit", retiredAt = Instant.parse("2026-07-01T12:00:00Z")),
            ),
        )

        assertEquals(listOf("meals", "old_habit"), items.allItems().map { it.id })
    }

    @Test
    fun measuredItemsAreSeparableFromAskedOnes() = dao {
        items.insertItems(
            listOf(Rows.item("meals"), Rows.item("steps", kind = ItemKind.MEASURED)),
        )

        assertEquals(listOf("steps"), items.itemsOfKind(ItemKind.MEASURED.name).map { it.id })
        assertEquals(listOf("meals"), items.itemsOfKind(ItemKind.ASKED.name).map { it.id })
    }

    /**
     * Spec constraint 6: rewording a question creates a new version and must not retroactively
     * change what an older answer meant. So the version in force on a date is the latest one that
     * had taken effect by then -- the same effective-from shape as target resolution.
     */
    @Test
    fun theVersionInForceIsTheLatestThatHadTakenEffect() = dao {
        items.insertItems(listOf(Rows.item("meals")))
        items.insertVersions(
            listOf(
                Rows.version("meals-v1", "meals", versionNo = 1, effectiveFrom = "2026-08-01"),
                Rows.version("meals-v2", "meals", versionNo = 2, effectiveFrom = "2026-08-20"),
            ),
        )

        assertEquals("meals-v1", items.versionInForce("meals", "2026-08-19")?.id)
        assertEquals("meals-v2", items.versionInForce("meals", "2026-08-20")?.id)
        assertEquals("meals-v2", items.versionInForce("meals", "2026-09-30")?.id)
    }

    @Test
    fun thereIsNoVersionInForceBeforeTheFirstOneTookEffect() = dao {
        items.insertItems(listOf(Rows.item("meals")))
        items.insertVersions(listOf(Rows.version("meals-v1", "meals", effectiveFrom = "2026-08-01")))

        assertNull(items.versionInForce("meals", "2026-07-31"))
    }

    /**
     * Options hang off the item, not the version (architecture section 5), so adding one does not
     * bump the version. A retired option must still resolve, or a past answer referencing it renders
     * as a blank.
     */
    @Test
    fun retiredOptionsStayAvailableForHistoryButLeaveTheCheckInList() = dao {
        items.insertItems(listOf(Rows.item("pre_sleep")))
        items.insertVersions(
            listOf(Rows.version("pre_sleep-v1", "pre_sleep", answerType = AnswerType.MULTI_SELECT)),
        )
        items.insertOptions(
            listOf(
                Rows.option("read_a_book", "pre_sleep", ordinal = 0),
                Rows.option(
                    "watched_dvd",
                    "pre_sleep",
                    ordinal = 1,
                    retiredAt = Instant.parse("2026-08-15T12:00:00Z"),
                ),
            ),
        )

        assertEquals(
            listOf("read_a_book", "watched_dvd"),
            items.optionsFor("pre_sleep").map { it.id },
        )
        assertEquals(listOf("read_a_book"), items.activeOptionsFor("pre_sleep").map { it.id })
    }

    /** Spec constraint 17: the no-opportunity option is a flag on the option, not an answer state. */
    @Test
    fun theNoOpportunityOptionIsMarkedOnTheOptionItself() = dao {
        items.insertItems(listOf(Rows.item("took_time")))
        items.insertOptions(
            listOf(
                Rows.option("yes", "took_time", ordinal = 0),
                Rows.option("no", "took_time", ordinal = 1),
                Rows.option("no_opportunity", "took_time", ordinal = 2, isNoOpportunity = true),
            ),
        )

        val flagged = items.optionsFor("took_time").filter { it.isNoOpportunity }
        assertEquals(listOf("no_opportunity"), flagged.map { it.id })
    }

    @Test
    fun optionsComeBackInTheirDeclaredOrder() = dao {
        items.insertItems(listOf(Rows.item("pre_sleep")))
        items.insertOptions(
            listOf(
                Rows.option("c", "pre_sleep", ordinal = 2),
                Rows.option("a", "pre_sleep", ordinal = 0),
                Rows.option("b", "pre_sleep", ordinal = 1),
            ),
        )

        assertEquals(listOf("a", "b", "c"), items.optionsFor("pre_sleep").map { it.id })
    }

    @Test
    fun versionsComeBackOldestFirst() = dao {
        items.insertItems(listOf(Rows.item("meals")))
        items.insertVersions(
            listOf(
                Rows.version("meals-v2", "meals", versionNo = 2, effectiveFrom = "2026-08-20"),
                Rows.version("meals-v1", "meals", versionNo = 1, effectiveFrom = "2026-08-01"),
            ),
        )

        assertTrue(items.versionsFor("meals").map { it.versionNo } == listOf(1, 2))
    }
}
