package com.zdredge.consistency.data.export

import com.zdredge.consistency.data.db.ConsistencyDatabase
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId

/**
 * The full-database JSON export (spec §2, build-order M11): [SnapshotReader] in, [JsonSnapshot] out.
 *
 * The counts it returns are read off the same snapshot that was written, not queried again
 * afterwards, so they describe the file -- which is why they are fit to show the user as evidence the
 * export worked.
 *
 * Unlike `DatabaseSnapshot`, which copies the SQLite file and is directly restorable, this file needs
 * an importer to go back in. None exists in the app; `JsonExportTest` performs a restore in test code
 * so the file is shown to be restorable rather than believed to be.
 */
object JsonExport {

    /** Writes the export to [out] as UTF-8. The caller owns and closes [out]. */
    fun writeTo(db: ConsistencyDatabase, exportedAt: Instant, zone: ZoneId, out: OutputStream): SnapshotSummary {
        val data = SnapshotReader.read(db.openHelper.writableDatabase, exportedAt, zone)

        val counting = CountingOutputStream(out)
        // Flushed, not closed: closing the writer would close the caller's stream.
        counting.bufferedWriter(Charsets.UTF_8).let {
            JsonSnapshot.write(data, it)
            it.flush()
        }

        fun rows(table: String) = data.tables.firstOrNull { it.name == table }?.rows?.size ?: 0
        return SnapshotSummary(
            bytes = counting.count,
            checkIns = rows("checkins"),
            answers = rows("answers"),
            measuredValues = rows("measured_values"),
            rolloverRuns = rows("rollover_runs"),
        )
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
