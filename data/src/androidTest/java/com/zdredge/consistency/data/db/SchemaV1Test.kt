package com.zdredge.consistency.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema v1, asserted against the database rather than against the annotations.
 *
 * These deliberately use raw SQL instead of the DAOs. The claim under test is that *SQLite* refuses
 * a duplicate answer and a dangling reference; a DAO test would only show that the DAO does, which
 * leaves the rollover job and the debug fixture free to write rows the check-in screen would have
 * rejected.
 */
@RunWith(AndroidJUnit4::class)
class SchemaV1Test {

    private lateinit var db: ConsistencyDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun schemaHoldsTheElevenTablesArchitectureSection5Describes() {
        val expected = setOf(
            "items",
            "item_versions",
            "select_options",
            "targets",
            "container_sizes",
            "roll_up_specs",
            "checkins",
            "answers",
            "answer_selections",
            "measured_values",
            "measured_origins",
        )

        val found = mutableSetOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor ->
                while (cursor.moveToNext()) found += cursor.getString(0)
            }

        assertTrue("missing tables: ${expected - found}", found.containsAll(expected))
    }

    /**
     * Spec constraint 15: every table carries `user_id` from the first schema, unused. Asserted for
     * all eleven at once because the failure mode is forgetting one, and one missing column is a
     * migration across the whole database later.
     */
    @Test
    fun everyTableCarriesUserIdFromTheFirstSchema() {
        val tables = listOf(
            "items", "item_versions", "select_options", "targets", "container_sizes",
            "roll_up_specs", "checkins", "answers", "answer_selections", "measured_values",
            "measured_origins",
        )

        val without = tables.filterNot { table ->
            db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }
                    .any { it == "user_id" }
            }
        }

        assertEquals("tables missing user_id", emptyList<String>(), without)
    }

    /**
     * One answer row per item per day (docs/CLAUDE.md conventions), enforced by the database.
     */
    @Test
    fun aSecondAnswerForTheSameItemAndDayIsRejectedByTheDatabase() {
        seedOneItemAndVersion()
        insertAnswer(id = "a1", day = "2026-08-25")

        assertThrows(SQLiteConstraintException::class.java) {
            insertAnswer(id = "a2", day = "2026-08-25")
        }
    }

    /**
     * The same item answered on a *different* day is ordinary and must still be allowed -- the
     * constraint above is easy to over-tighten into "one answer per item".
     */
    @Test
    fun theSameItemMayBeAnsweredOnDifferentDays() {
        seedOneItemAndVersion()
        insertAnswer(id = "a1", day = "2026-08-25")
        insertAnswer(id = "a2", day = "2026-08-26")

        assertEquals(2, countOf("answers"))
    }

    /**
     * A check-in was either expected on a day in a slot or it was not. Two rows would double-count
     * the denominator of the primary metric.
     */
    @Test
    fun aSecondCheckInForTheSameDayAndSlotIsRejectedByTheDatabase() {
        insertCheckIn(id = "c1", day = "2026-08-25", slot = "NIGHT")

        assertThrows(SQLiteConstraintException::class.java) {
            insertCheckIn(id = "c2", day = "2026-08-25", slot = "NIGHT")
        }
    }

    /** Both slots on one day are normal: the night check-in and the next morning's. */
    @Test
    fun bothSlotsMayBeExpectedOnTheSameDay() {
        insertCheckIn(id = "c1", day = "2026-08-25", slot = "NIGHT")
        insertCheckIn(id = "c2", day = "2026-08-25", slot = "MORNING")

        assertEquals(2, countOf("checkins"))
    }

    /** Foreign keys are enforced, so an answer cannot reference an item that does not exist. */
    @Test
    fun anAnswerCannotReferenceAnItemThatDoesNotExist() {
        assertThrows(SQLiteConstraintException::class.java) {
            insertAnswer(id = "a1", day = "2026-08-25")
        }
    }

    private fun seedOneItemAndVersion() {
        exec(
            "INSERT INTO items (id, user_id, kind, created_at, retired_at) " +
                "VALUES ('meals', 'local', 'ASKED', 0, NULL)",
        )
        exec(
            "INSERT INTO item_versions " +
                "(id, user_id, item_id, version_no, prompt, answer_type, classification, slot, " +
                "unit_label, effective_from) " +
                "VALUES ('meals-v1', 'local', 'meals', 1, 'Meals', 'NUMBER', 'GOAL', 'NIGHT', " +
                "NULL, '2026-08-01')",
        )
    }

    private fun insertAnswer(id: String, day: String) = exec(
        "INSERT INTO answers " +
            "(id, user_id, item_id, item_version_id, day_date, submitted_via_checkin_id, " +
            "submitted_at, capture, edited_at, value_bool, value_number, value_time, value_scale, " +
            "note) " +
            "VALUES ('$id', 'local', 'meals', 'meals-v1', '$day', NULL, 0, 'IN_WINDOW', NULL, " +
            "NULL, 3.0, NULL, NULL, NULL)",
    )

    private fun insertCheckIn(id: String, day: String, slot: String) = exec(
        "INSERT INTO checkins " +
            "(id, user_id, day_date, slot, scheduled_at, state, answered_at, notify_attempts) " +
            "VALUES ('$id', 'local', '$day', '$slot', 0, 'PENDING', NULL, 0)",
    )

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private fun countOf(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
