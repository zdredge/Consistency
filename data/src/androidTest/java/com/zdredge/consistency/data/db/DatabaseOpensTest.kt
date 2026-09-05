package com.zdredge.consistency.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The toolchain smoke test: Room's annotation processor ran under KSP against AGP 9, and the
 * database it generated actually opens on the device.
 *
 * This runs instrumented rather than on the JVM deliberately (docs/CLAUDE.md testing). Migrations
 * and non-trivial queries are precisely the things a stubbed or re-implemented SQLite would lie
 * about, so :data is proven against the SQLite that ships on the Pixel.
 *
 * Note this is JUnit 4, not the JUnit 5 used in :domain. Android instrumentation runs on JUnit 4;
 * the split is a platform fact rather than an inconsistency.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseOpensTest {

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

    /**
     * The version is deliberately not pinned here. `SchemaVersionPinTest` owns that, and a number
     * asserted in two places is a number that gets updated in one of them -- this test failed on the
     * v2 bump for no reason of its own, which is exactly that cost showing up.
     */
    @Test
    fun databaseOpensAndReportsASchemaVersion() {
        val version = db.openHelper.writableDatabase.version
        assertTrue("a built database must report a positive schema version, was $version", version >= 1)
    }
}
