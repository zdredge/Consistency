package com.zdredge.consistency.data.export

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.ZoneId

/**
 * Reads the whole database into a [SnapshotData] for [JsonSnapshot] to write.
 *
 * **Generic on purpose.** It lists the tables from `sqlite_master` and every column as stored, rather
 * than mapping each Room entity by hand. A table or column added later is exported without anyone
 * remembering to add it, which makes the export lossless by construction rather than by
 * maintenance -- the failure that matters here is a field that silently stopped being exported.
 *
 * **One transaction around every read**, so the file is a single moment: the rollover cannot mark a
 * check-in missed between the `checkins` read and the `answers` read and leave the two disagreeing.
 * The raw file copy (`DatabaseSnapshot`) cannot promise that.
 *
 * **No checkpoint.** The reads go through the SQLite connection, which sees the write-ahead log, so
 * the stale-file problem `DatabaseSnapshot` exists to solve does not arise here.
 */
object SnapshotReader {

    /** Room's bookkeeping and SQLite's own tables. Neither is the user's data. */
    private val NOT_DATA = setOf("android_metadata", "room_master_table")

    fun read(db: SupportSQLiteDatabase, exportedAt: Instant, zone: ZoneId): SnapshotData {
        // IMMEDIATE rather than a plain deferred BEGIN: the rollover runs from the background, and a
        // writer arriving mid-read is held off until the snapshot is taken rather than racing it.
        db.beginTransactionNonExclusive()
        try {
            return SnapshotData(
                schemaVersion = db.single("PRAGMA user_version") { it.getInt(0) },
                identityHash = db.single("SELECT identity_hash FROM room_master_table WHERE id = 42") {
                    it.getString(0)
                },
                exportedAt = exportedAt,
                zone = zone,
                tables = tableNames(db).map { readTable(db, it) },
            )
        } finally {
            // Nothing was written, so there is nothing to commit; ending without success is a no-op.
            db.endTransaction()
        }
    }

    private fun tableNames(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            .filterNot { it in NOT_DATA }

    /**
     * Rows in rowid order -- insertion order, which for these tables is roughly the order things
     * happened, and stable from one export to the next.
     */
    private fun readTable(db: SupportSQLiteDatabase, name: String): TableData =
        db.query("SELECT * FROM \"${name.replace("\"", "\"\"")}\" ORDER BY rowid").use { cursor ->
            val columns = cursor.columnNames.toList()
            val rows = buildList {
                while (cursor.moveToNext()) add(columns.indices.map { cell(cursor, it) })
            }
            TableData(name, columns, rows)
        }

    /**
     * Each value typed as SQLite actually holds it, which is not always the column's declared
     * affinity -- so the type is asked per cell rather than assumed per column.
     */
    private fun cell(cursor: Cursor, index: Int): Cell = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> Cell.Null
        Cursor.FIELD_TYPE_INTEGER -> Cell.Integer(cursor.getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> Cell.Real(cursor.getDouble(index))
        Cursor.FIELD_TYPE_STRING -> Cell.Text(cursor.getString(index))
        else -> Cell.Blob
    }

    private fun <T> SupportSQLiteDatabase.single(sql: String, read: (Cursor) -> T): T =
        query(sql).use { check(it.moveToFirst()) { "no result for: $sql" }; read(it) }
}
