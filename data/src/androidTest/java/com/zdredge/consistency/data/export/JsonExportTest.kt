package com.zdredge.consistency.data.export

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdredge.consistency.data.ConsistencyRepository
import com.zdredge.consistency.data.db.ConsistencyDatabase
import com.zdredge.consistency.data.db.createConsistencyDatabase
import com.zdredge.consistency.domain.model.Answer
import com.zdredge.consistency.domain.model.Capture
import com.zdredge.consistency.domain.model.ItemId
import com.zdredge.consistency.domain.model.ItemVersionId
import com.zdredge.consistency.domain.model.MeasuredOrigin
import com.zdredge.consistency.domain.model.MeasuredState
import com.zdredge.consistency.domain.model.MeasuredValue
import com.zdredge.consistency.domain.model.OptionId
import com.zdredge.consistency.domain.model.Slot
import com.zdredge.consistency.domain.time.DayResolver
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * That the JSON export holds the whole database, exactly, and **can actually be restored**.
 *
 * Every export before this one was a file only *believed* to be restorable -- nothing had ever read
 * one back. Here a restore is performed: the file is parsed, every row is inserted into a fresh
 * database of the same schema, and every table is compared cell for cell with the one it came from.
 *
 * File-backed, not in-memory, for the same reason as `DatabaseSnapshotTest`: the risk worth guarding
 * includes rows still sitting in the write-ahead log, and an in-memory database has no log.
 *
 * The fixture is built through the repository, so the rows are the ones the app really writes: a note
 * with quotes, a newline and emoji; a half bottle; a deferral; an answer edited through a later
 * check-in; single and multi selections; a day two step sources reported; a retired option; and a
 * rollover run.
 */
@RunWith(AndroidJUnit4::class)
class JsonExportTest {

    private val zone = ZoneId.of("America/New_York")
    private val day1 = LocalDate.of(2026, 9, 1)
    private val day2 = day1.plusDays(1)
    private val exportedAt = LocalDateTime.parse("2026-09-04T10:00").atZone(zone).toInstant()

    private val note = "watched \"the game\"\nthen read 📚 — café"

    private lateinit var context: Context
    private lateinit var db: ConsistencyDatabase
    private var restored: ConsistencyDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Distinct names, so a failing test can never touch the real database.
        context.deleteDatabase(SOURCE_DB)
        context.deleteDatabase(RESTORE_DB)
        db = createConsistencyDatabase(context, SOURCE_DB)

        runBlocking {
            repoAt("2026-09-01T10:00").seedLibraryIfEmpty(day1)

            val night = repoAt("2026-09-01T21:30")
            night.ensureCheckInsExist(day1)
            night.recordAnswer(answer("meals", day1, valueNumber = 3.0, note = note), day1, Slot.NIGHT)
            night.recordAnswer(answer("water", day1, valueNumber = 1.5), day1, Slot.NIGHT)
            night.recordAnswer(answer("took_time", day1, selections = setOf("took_time.yes")), day1, Slot.NIGHT)
            night.recordAnswer(answer("vitamins", day1, capture = Capture.PENDING), day1, Slot.NIGHT)
            night.markCheckInAnsweredIfAnswered(day1, Slot.NIGHT, clockAt("2026-09-01T21:40").instant())

            val morning = repoAt("2026-09-02T08:30")
            morning.ensureCheckInsExist(day2)
            morning.recordAnswer(answer("bedtime", day1, valueTime = LocalTime.of(23, 30)), day2, Slot.MORNING)
            morning.recordAnswer(
                answer("pre_sleep", day1, selections = setOf("pre_sleep.read_a_book", "pre_sleep.watched_youtube")),
                day2,
                Slot.MORNING,
            )

            // Corrected through the next night's check-in, so it carries an edit stamp.
            val nextNight = repoAt("2026-09-02T21:30")
            nextNight.recordAnswer(answer("meals", day1, valueNumber = 4.0, note = note), day2, Slot.NIGHT)

            nextNight.recordMeasuredValue(
                MeasuredValue(
                    itemId = ItemId("steps"),
                    day = day1,
                    value = 18_000.0,
                    state = MeasuredState.CONFLICTED,
                    lastSyncedAt = clockAt("2026-09-02T08:31").instant(),
                    origins = listOf(MeasuredOrigin(PHONE, 9_000.0), MeasuredOrigin(WATCH, 9_000.0)),
                ),
            )

            repoAt("2026-09-04T04:15").runRollover()
        }

        db.openHelper.writableDatabase.execSQL(
            "UPDATE select_options SET retired_at = ? WHERE id = 'pre_sleep.watched_tv'",
            arrayOf(clockAt("2026-09-03T12:00").instant().toEpochMilli()),
        )
    }

    @After
    fun tearDown() {
        db.close()
        restored?.close()
        context.deleteDatabase(SOURCE_DB)
        context.deleteDatabase(RESTORE_DB)
    }

    /**
     * **Every table, every column.** The reader is generic precisely so that nothing has to be added
     * to it when the schema grows; this is the test that holds it to that.
     */
    @Test
    fun everyTableAndEveryColumnIsInTheFile() {
        val (_, json) = export()
        val tables = json.getJSONObject("tables")
        val source = db.openHelper.writableDatabase

        val expected = dataTables(source)
        assertEquals(expected.toSet(), tables.keys().asSequence().toSet())

        for (table in expected) {
            val exported = tables.getJSONObject(table)
            assertEquals("$table row count", count(source, table), exported.getInt("rowCount"))
            val rows = exported.getJSONArray("rows")
            val columns = columns(source, table)
            for (i in 0 until rows.length()) {
                assertEquals("$table row $i columns", columns.toSet(), rows.getJSONObject(i).keys().asSequence().toSet())
            }
        }
        assertTrue("the fixture must actually have written answers", count(source, "answers") > 0)
    }

    /**
     * Each value in the file is the stored value **with its stored type**: an integer stays an
     * integer, a REAL stays a REAL, text stays text, and a null stays null. A restore cannot catch a
     * REAL written as text -- SQLite's column affinity would quietly turn "1.5" back into 1.5 -- so
     * the file is checked against the source directly.
     */
    @Test
    fun everyValueInTheFileIsTheStoredValueWithItsStoredType() {
        val (_, json) = export()
        val tables = json.getJSONObject("tables")
        val source = db.openHelper.writableDatabase

        for (table in dataTables(source)) {
            val columns = columns(source, table)
            val stored = rows(source, table)
            val exported = tables.getJSONObject(table).getJSONArray("rows")
            assertEquals(table, stored.size, exported.length())

            stored.forEachIndexed { r, row ->
                val obj = exported.getJSONObject(r)
                row.forEachIndexed { c, value ->
                    assertEquals("$table[$r].${columns[c]}", value, fromJson(obj.get(columns[c])))
                }
            }
        }
    }

    /**
     * **A restore, performed.** Parse the file, insert every row into a fresh database of the same
     * schema, and compare every table cell for cell. Foreign keys are checked once everything is in,
     * so insertion order cannot matter but a reference to nothing still fails.
     */
    @Test
    fun theFileRestoresIntoAFreshDatabaseCellForCell() {
        val (_, json) = export()
        val target = createConsistencyDatabase(context, RESTORE_DB).also { restored = it }
        val sql = target.openHelper.writableDatabase
        val tables = json.getJSONObject("tables")

        sql.beginTransaction()
        try {
            sql.execSQL("PRAGMA defer_foreign_keys = ON")
            for (table in tables.keys()) {
                val rows = tables.getJSONObject(table).getJSONArray("rows")
                for (i in 0 until rows.length()) {
                    sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, contentValues(rows.getJSONObject(i)))
                }
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }

        sql.query("PRAGMA foreign_key_check").use {
            assertEquals("every reference in the restored database resolves", 0, it.count)
        }

        val source = db.openHelper.writableDatabase
        for (table in dataTables(source)) {
            assertEquals(table, rows(source, table), rows(sql, table))
        }

        // The worst case for the writer, read back out of the restored database.
        val restoredNote = sql.query("SELECT note FROM answers WHERE item_id = 'meals'").use {
            it.moveToFirst()
            it.getString(0)
        }
        assertEquals(note, restoredNote)
    }

    /**
     * Rows still in the write-ahead log are in the file. The fixture's writes have not been
     * checkpointed -- the log is non-empty when the export runs -- and the export reads through the
     * connection, which sees them. A reader that went to the `.db` file instead would miss them.
     */
    @Test
    fun rowsStillInTheWriteAheadLogAreExported() {
        val wal = context.getDatabasePath("$SOURCE_DB-wal")
        assertTrue("the fixture's writes must still be in the log for this to mean anything", wal.length() > 0)

        val (summary, json) = export()

        val live = count(db.openHelper.writableDatabase, "checkins")
        assertTrue(live > 0)
        assertEquals(live, json.getJSONObject("tables").getJSONObject("checkins").getInt("rowCount"))
        assertEquals(live, summary.checkIns)
    }

    /** The header says what the file is, when it was taken, and against which 04:00. */
    @Test
    fun theHeaderIdentifiesTheFile() {
        val (_, json) = export()

        assertEquals(JsonSnapshot.FORMAT, json.getString("format"))
        assertEquals(JsonSnapshot.FORMAT_VERSION, json.getInt("formatVersion"))
        assertEquals(exportedAt.toString(), json.getString("exportedAt"))
        assertEquals("America/New_York", json.getString("zone"))
        assertEquals(3, json.getInt("schemaVersion"))
        assertEquals(SCHEMA_V3_IDENTITY_HASH, json.getString("identityHash"))
        assertEquals(JsonSnapshot.ENCODING, json.getString("encoding"))
    }

    /** The counts shown to the user are the file's own, and the byte count is the file's length. */
    @Test
    fun theSummaryDescribesTheFile() {
        val out = ByteArrayOutputStream()
        val summary = runBlocking { repoAt("2026-09-04T10:00").writeJsonExportTo(out) }
        val tables = JSONObject(out.toString(Charsets.UTF_8.name())).getJSONObject("tables")

        assertEquals(out.size().toLong(), summary.bytes)
        assertEquals(tables.getJSONObject("checkins").getInt("rowCount"), summary.checkIns)
        assertEquals(tables.getJSONObject("answers").getInt("rowCount"), summary.answers)
        assertEquals(tables.getJSONObject("measured_values").getInt("rowCount"), summary.measuredValues)
        assertEquals(tables.getJSONObject("rollover_runs").getInt("rowCount"), summary.rolloverRuns)
        assertNotNull(tables.optJSONObject("measured_origins"))
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** Through the repository, as the app calls it, dated by the test clock. */
    private fun export(): Pair<SnapshotSummary, JSONObject> {
        val out = ByteArrayOutputStream()
        val summary = runBlocking { repoAt("2026-09-04T10:00").writeJsonExportTo(out) }
        return summary to JSONObject(out.toString(Charsets.UTF_8.name()))
    }

    /** The user's tables, found independently of the reader under test. */
    private fun dataTables(sql: SupportSQLiteDatabase): List<String> =
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.filterNot { it.startsWith("sqlite_") || it == "android_metadata" || it == "room_master_table" }

    private fun columns(sql: SupportSQLiteDatabase, table: String): List<String> =
        sql.query("PRAGMA table_info(\"$table\")").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    private fun count(sql: SupportSQLiteDatabase, table: String): Int =
        sql.query("SELECT COUNT(*) FROM \"$table\"").use { it.moveToFirst(); it.getInt(0) }

    /** Every row, every cell, typed as SQLite holds it: Long, Double, String or null. */
    private fun rows(sql: SupportSQLiteDatabase, table: String): List<List<Any?>> =
        sql.query("SELECT * FROM \"$table\" ORDER BY rowid").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        (0 until c.columnCount).map { i ->
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                else -> error("$table holds a blob")
                            }
                        },
                    )
                }
            }
        }

    /** A JSON value as the same Kotlin type [rows] produces. `org.json` reads small integers as Int. */
    private fun fromJson(value: Any?): Any? = when (value) {
        JSONObject.NULL, null -> null
        is Int -> value.toLong()
        is Long, is Double, is String -> value
        else -> error("unexpected JSON value ${value::class.simpleName}: $value")
    }

    /** The test-only importer's one row. */
    private fun contentValues(row: JSONObject) = ContentValues().apply {
        for (key in row.keys()) {
            when (val v = fromJson(row.get(key))) {
                null -> putNull(key)
                is Long -> put(key, v)
                is Double -> put(key, v)
                is String -> put(key, v)
            }
        }
    }

    private fun answer(
        item: String,
        day: LocalDate,
        capture: Capture = Capture.IN_WINDOW,
        valueNumber: Double? = null,
        valueTime: LocalTime? = null,
        selections: Set<String> = emptySet(),
        note: String? = null,
    ) = Answer(
        itemId = ItemId(item),
        itemVersionId = ItemVersionId("$item.v1"),
        day = day,
        capture = capture,
        submittedAt = Instant.EPOCH,
        valueNumber = valueNumber,
        valueTime = valueTime,
        selections = selections.map(::OptionId).toSet(),
        note = note,
    )

    private fun clockAt(local: String): Clock =
        Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)

    private fun repoAt(local: String) = ConsistencyRepository(db, DayResolver(clockAt(local)))

    private companion object {
        const val SOURCE_DB = "json-export-test.db"
        const val RESTORE_DB = "json-restore-test.db"
        const val PHONE = "com.android.healthconnect.phone.j94257314766b3142a71ff5cce8f3ca59"
        const val WATCH = "com.samsung.health"

        /** Pinned in `SchemaVersionPinTest`; the export must carry the same one. */
        const val SCHEMA_V3_IDENTITY_HASH = "e9f6b5986bf3314f7a9d0f34b90cb194"
    }
}
