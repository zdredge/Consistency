package com.zdredge.consistency.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zdredge.consistency.data.db.ConsistencyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before

/**
 * An in-memory database per test, on the device.
 *
 * In-memory rather than on-disk keeps tests independent without deleting files between them; it is
 * still the device's own SQLite, which is the whole reason these run instrumented rather than on the
 * JVM (docs/CLAUDE.md).
 */
internal abstract class DaoTestBase {

    protected lateinit var db: ConsistencyDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ConsistencyDatabase::class.java,
        ).build()
    }

    @After
    fun closeDb() = db.close()

    protected fun <T> dao(block: suspend () -> T): T = runBlocking { block() }
}
