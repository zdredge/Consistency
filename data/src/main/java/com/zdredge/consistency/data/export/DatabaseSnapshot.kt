package com.zdredge.consistency.data.export

import com.zdredge.consistency.data.db.ConsistencyDatabase
import java.io.File
import java.io.OutputStream

/**
 * What a snapshot contains, so the caller can say it out loud rather than claim success blindly.
 *
 * Counts are read *after* the checkpoint, from the same file that was copied, which is what makes
 * them evidence rather than decoration: if the copy were stale these numbers would be stale too, and
 * they are the numbers shown to the user.
 */
data class SnapshotSummary(
    val bytes: Long,
    val checkIns: Int,
    val answers: Int,
    val measuredValues: Int,
    val rolloverRuns: Int,
)

/**
 * Copies the database out, whole.
 *
 * **The checkpoint is the entire point of this class.** Room runs in WAL mode and only folds the
 * write-ahead log into the main file at a checkpoint, which it does automatically at around 4 MB —
 * a threshold this database will not reach for a very long time. Measured on the real device before
 * this existed: the `.db` file held 5 check-ins, no step values and no rollover runs, while the file
 * plus its `-wal` held 9, 1 and 2. **A copy of `consistency.db` alone was silently two days out of
 * date**, and the days it lost were the most recent ones.
 *
 * So `wal_checkpoint(TRUNCATE)` runs first, folding everything into the main file and emptying the
 * log, and only then is the file copied. The alternative — copying `.db`, `-wal` and `-shm` together
 * — also works but produces three files to keep together and one restore path to get wrong.
 *
 * **This is a copy, not a backup, and the difference matters.** Nothing reads these files back yet.
 * Until a restore has actually been performed, an export is a file that is *believed* to be
 * restorable. Do not describe it to the user as a backup.
 */
object DatabaseSnapshot {

    /**
     * Checkpoints, then streams the database file to [out]. The caller owns and closes [out].
     *
     * Not atomic against a concurrent write, and deliberately not pretending to be: the rollover
     * could in principle fire during the copy. The realistic consequence is a snapshot missing the
     * last few rows, not a corrupt one, and the honest fix for that is to export when you are looking
     * at the app rather than to hold a database lock open across file I/O.
     */
    fun writeTo(db: ConsistencyDatabase, databaseFile: File, out: OutputStream): SnapshotSummary {
        val sql = db.openHelper.writableDatabase

        // TRUNCATE rather than PASSIVE: PASSIVE gives up rather than waiting for readers, and a
        // checkpoint that quietly did nothing is exactly the failure this exists to prevent.
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }

        val bytes = databaseFile.inputStream().use { it.copyTo(out) }

        return SnapshotSummary(
            bytes = bytes,
            checkIns = sql.count("checkins"),
            answers = sql.count("answers"),
            measuredValues = sql.count("measured_values"),
            rolloverRuns = sql.count("rollover_runs"),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
