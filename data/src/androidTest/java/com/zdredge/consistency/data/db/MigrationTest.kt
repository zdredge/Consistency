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

    private companion object {
        const val TEST_DB = "migration-1-2-test.db"
    }
}
