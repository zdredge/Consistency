package com.zdredge.consistency.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration machinery, proven before there is a migration to run.
 *
 * **There are no migrations at M3.** There is only schema v1, so a test claiming to verify one would
 * be theatre. What is worth proving now is that the apparatus works, because it is unprovable later:
 * a migration test rebuilds the *old* schema from the exported JSON, so if that export is missing,
 * stale, or not packaged into the test APK, the first real migration arrives with no way to check it
 * and the discovery happens against a database that already holds real history.
 *
 * Building the whole schema from the export is exactly what caught a stale-asset bug in M3: the task
 * that copies schemas into the androidTest assets did not re-run on an incremental build, so this
 * helper was reading a schema one revision behind the code. `data/build.gradle.kts` now forces that
 * copy. The version pin lives in a JVM test (`SchemaVersionPinTest`) that reads the committed file
 * rather than the packaged copy, for the same reason.
 */
@RunWith(AndroidJUnit4::class)
class MigrationScaffoldTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ConsistencyDatabase::class.java,
    )

    /** The exported schema is present, packaged into the test APK, and sufficient to build from. */
    @Test
    fun schemaVersion1CanBeRebuiltFromItsExportedDefinition() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }

            assertTrue(
                "the exported v1 schema must contain the eleven tables, found $tables",
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
     * A v1 database built from the export opens under the current entity definitions.
     *
     * The database is built from the *file* and validated against the *code*, running every
     * migration in between. Per-migration assertions live in `MigrationTest`; this one is the
     * end-to-end path an existing install actually takes on upgrade.
     */
    @Test
    fun aVersion1DatabaseOpensUnderTheCurrentSchemaOnceMigrated() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(TEST_DB, 2, true, *ALL_MIGRATIONS).close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
