package com.zdredge.consistency.data.export

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.data.db.createConsistencyDatabase
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * That an exported file actually contains the data.
 *
 * **This has to run against a file-backed database, not the in-memory one every other test uses.**
 * The whole risk being guarded lives in the write-ahead log: Room only folds the WAL into the main
 * `.db` file at a checkpoint, and it does that automatically at around 4 MB — a size this database
 * will not reach for years. Measured on the real device before the export existed, the `.db` file
 * held 5 check-ins where the file plus its `-wal` held 9. **A copy of `consistency.db` alone was two
 * days stale, and the days it had lost were the most recent ones.**
 *
 * An in-memory database has no file and therefore no WAL, so it would pass this test no matter what
 * the export did.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSnapshotTest {

    private val zone = ZoneId.of("America/New_York")
    private val installDay = LocalDate.of(2026, 9, 1)

    private lateinit var db: ConsistencyDatabase
    private lateinit var dbFile: File
    private lateinit var exported: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // A distinct name, so a failing test can never touch the real database.
        context.deleteDatabase(TEST_DB)
        db = createConsistencyDatabase(context, TEST_DB)
        dbFile = context.getDatabasePath(TEST_DB)
        exported = File.createTempFile("snapshot", ".db", context.cacheDir)

        runBlocking {
            val repo = repoAt("2026-09-01T10:00")
            repo.seedLibraryIfEmpty(installDay)
            repo.ensureCheckInsExist(installDay.plusDays(2))
        }
    }

    @After
    fun tearDown() {
        db.close()
        exported.delete()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(TEST_DB)
    }

    /**
     * **The regression test for the whole export.**
     *
     * Written rows are still in the WAL when this runs, so an export that skipped the checkpoint and
     * copied the file would produce a database missing every check-in. Remove the
     * `wal_checkpoint(TRUNCATE)` from `DatabaseSnapshot` and this fails.
     */
    @Test
    fun anExportedFileStandsOnItsOwnAndHasEverythingInIt() {
        val live = runBlocking { repoAt("2026-09-03T10:00").checkIns(installDay, installDay.plusDays(2)) }
        assertTrue("the fixture must actually have written something", live.isNotEmpty())

        val summary = exported.outputStream().use {
            DatabaseSnapshot.writeTo(db, dbFile, it)
        }

        assertEquals(live.size, summary.checkIns)
        assertEquals(
            "the file alone, with no -wal beside it, holds every check-in",
            live.size,
            countInExportedFile("checkins"),
        )
        assertEquals(
            "and the whole seeded library",
            16,
            countInExportedFile("items"),
        )
        assertTrue("a non-empty file was written", summary.bytes > 0)
    }

    /**
     * The export is idempotent and repeatable — it is the thing you are meant to run often, and a
     * second run must not depend on the first having happened or not.
     */
    @Test
    fun exportingTwiceProducesTheSameCounts() {
        val first = exported.outputStream().use { DatabaseSnapshot.writeTo(db, dbFile, it) }
        val second = exported.outputStream().use { DatabaseSnapshot.writeTo(db, dbFile, it) }

        assertEquals(first.checkIns, second.checkIns)
        assertEquals(first.answers, second.answers)
    }

    /** Opens the exported file with no `-wal` or `-shm` beside it — the point of the exercise. */
    private fun countInExportedFile(table: String): Int =
        SQLiteDatabase.openDatabase(
            exported.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { standalone ->
            standalone.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }

    private fun repoAt(local: String) = ConsistencyRepository(
        db,
        DayResolver(Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)),
    )

    private companion object {
        const val TEST_DB = "export-test.db"
    }
}
