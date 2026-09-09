package com.zdredge.consistency.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real migration tests. One per migration, as build-order requires.
 *
 * M3 could only build the scaffolding, because there was one schema and nothing to migrate. This is
 * the first exercise of it, and the reason the exported schemas are committed: each test below
 * creates the **old** database from its exported JSON, so without those files there would be nothing
 * to migrate from and a migration would be unverifiable after the fact.
 *
 * What makes these worth running rather than assuming: v1 is installed on a real device holding real
 * answers. A migration that drops or rewrites those rows fails silently in the worst way — the app
 * opens and the history is simply different.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ConsistencyDatabase::class.java,
    )

    /**
     * v1 → v2 adds `items.ordinal`. Room validates the migrated schema against the compiled entities
     * as part of `runMigrationsAndValidate`, so this failing means the ALTER and the `@ColumnInfo`
     * default have drifted apart — an easy mistake, since Room compares defaults column for column.
     */
    @Test
    fun migrating1To2AddsTheOrdinalColumn() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO items (id, user_id, kind, created_at, retired_at) " +
                    "VALUES ('meals', 'local', 'ASKED', 0, NULL)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info(`items`)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1)
            }
            assertTrue("ordinal must exist after the migration", "ordinal" in columns)
        }
    }

    /**
     * **The assertion that matters.** An existing install's items survive the migration and take the
     * column's default rather than vanishing. This is what `ALTER TABLE ... ADD COLUMN` with a
     * `DEFAULT` buys, and what a create-copy-drop-rename migration is far easier to get wrong.
     */
    @Test
    fun migrating1To2PreservesExistingItemsAndDefaultsTheirOrdinalToZero() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO items (id, user_id, kind, created_at, retired_at) VALUES " +
                    "('meals', 'local', 'ASKED', 111, NULL), " +
                    "('steps', 'local', 'MEASURED', 222, NULL)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            val rows = mutableListOf<Triple<String, String, Int>>()
            db.query("SELECT id, kind, ordinal FROM items ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    rows += Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
                }
            }

            assertEquals(
                listOf(
                    Triple("meals", "ASKED", 0),
                    Triple("steps", "MEASURED", 0),
                ),
                rows,
            )
        }
    }

    /** Nothing else is disturbed: the other ten tables come through untouched. */
    @Test
    fun migrating1To2LeavesEveryOtherTableAlone() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue(
                "expected all eleven tables, found $tables",
                tables.containsAll(
                    listOf(
                        "items", "item_versions", "select_options", "targets", "container_sizes",
                        "roll_up_specs", "checkins", "answers", "answer_selections",
                        "measured_values", "measured_origins",
                    ),
                ),
            )
        }
    }

    /**
     * v2 → v3 adds `rollover_runs`, the 04:00 job's record of its own executions.
     *
     * A new table with no foreign keys, so the risk here is not data loss but drift: the hand-written
     * CREATE must match the compiled entity exactly, including nullability and the index, or Room's
     * post-migration validation refuses to open the database on a real device holding real history.
     * `runMigrationsAndValidate` is what catches that, and it is the only reason this test is worth
     * more than reading the SQL.
     */
    @Test
    fun migrating2To3AddsTheRolloverRunsTable() {
        helper.createDatabase(TEST_DB_2_3, 2).close()

        helper.runMigrationsAndValidate(TEST_DB_2_3, 3, true, MIGRATION_2_3).use { db ->
            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info(`rollover_runs`)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1)
            }

            assertEquals(
                listOf(
                    "id", "user_id", "ran_at", "for_day", "outcome",
                    "checkins_created", "checkins_missed", "values_frozen", "error",
                ),
                columns,
            )
        }
    }

    /**
     * An existing install's history is untouched by v3. The whole migration is one CREATE, and this
     * is the assertion that says so rather than assuming it.
     */
    @Test
    fun migrating2To3PreservesExistingRows() {
        helper.createDatabase(TEST_DB_2_3_DATA, 2).use { db ->
            db.execSQL(
                "INSERT INTO items (id, user_id, kind, created_at, retired_at, ordinal) " +
                    "VALUES ('meals', 'local', 'ASKED', 111, NULL, 3)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB_2_3_DATA, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT id, ordinal FROM items").use { cursor ->
                assertTrue("the item must survive", cursor.moveToNext())
                assertEquals("meals", cursor.getString(0))
                assertEquals(3, cursor.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM rollover_runs").use { cursor ->
                cursor.moveToNext()
                assertEquals("a new table starts empty", 0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-1-2-test.db"
        const val TEST_DB_2_3 = "migration-2-3-test.db"
        const val TEST_DB_2_3_DATA = "migration-2-3-data-test.db"
    }
}
