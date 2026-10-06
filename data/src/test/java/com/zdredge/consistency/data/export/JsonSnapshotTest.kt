package com.zdredge.consistency.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The export's writer: rows in, JSON out.
 *
 * **The export is the one file meant to outlive the phone**, so a serialiser that quietly mangles a
 * note, rounds a number or drops a null writes a record that looks complete and is not -- and nobody
 * finds out until the day it is needed. That is why this is pure, runs on the JVM, and pins its
 * output byte for byte rather than checking that it "looks like JSON".
 *
 * Whether a real database comes back out of the file intact is `JsonExportTest`'s question, on the
 * device; this one is about the writer alone.
 */
class JsonSnapshotTest {

    private val exportedAt = Instant.parse("2026-09-29T02:14:00Z")
    private val zone = ZoneId.of("America/New_York")

    private fun snapshot(vararg tables: TableData) = SnapshotData(
        schemaVersion = 3,
        identityHash = "abc123",
        exportedAt = exportedAt,
        zone = zone,
        tables = tables.toList(),
    )

    private fun write(data: SnapshotData): String = StringBuilder().also { JsonSnapshot.write(data, it) }.toString()

    /** A value on its own, as it appears inside a one-cell row. */
    private fun cell(value: Cell): String {
        val json = write(snapshot(TableData("t", listOf("c"), listOf(listOf(value)))))
        return json.substringAfter("{\"c\": ").substringBefore("}\n")
    }

    @Test
    fun theWholeFileHasTheAgreedShape() {
        val json = write(
            snapshot(
                TableData(
                    "answers",
                    listOf("id", "value_number", "note"),
                    listOf(
                        listOf(Cell.Text("a1"), Cell.Real(3.0), Cell.Null),
                        listOf(Cell.Text("a2"), Cell.Real(1.5), Cell.Text("a bike ride")),
                    ),
                ),
                TableData("checkins", listOf("id"), emptyList()),
            ),
        )

        assertEquals(
            """
            {
              "format": "consistency-export",
              "formatVersion": 1,
              "exportedAt": "2026-09-29T02:14:00Z",
              "zone": "America/New_York",
              "schemaVersion": 3,
              "identityHash": "abc123",
              "encoding": "${JsonSnapshot.ENCODING}",
              "tables": {
                "answers": {
                  "rowCount": 2,
                  "rows": [
                    {"id": "a1", "value_number": 3.0, "note": null},
                    {"id": "a2", "value_number": 1.5, "note": "a bike ride"}
                  ]
                },
                "checkins": {
                  "rowCount": 0,
                  "rows": []
                }
              }
            }
            """.trimIndent() + "\n",
            json,
        )
    }

    /**
     * Notes are free text and are where anything can turn up. Every character JSON requires escaped
     * is escaped; everything else -- accents, emoji -- is written as itself, which is valid UTF-8
     * JSON and readable in any editor.
     */
    @Test
    fun textIsEscapedExactlyWhereJsonRequiresIt() {
        assertEquals(
            "\"watched \\\"the game\\\"\\\\ at 9\\nthen read\\tin bed\\r\\b\\f\\u0001\"",
            cell(Cell.Text("watched \"the game\"\\ at 9\nthen read\tin bed\r\b\u000c\u0001")),
        )
        assertEquals("\"café ☕ 😴\"", cell(Cell.Text("café ☕ 😴")))
    }

    /**
     * A lone surrogate cannot be encoded as UTF-8 and would silently become `?` on the way out. It is
     * written as an escape instead, so the exact string survives.
     */
    @Test
    fun aLoneSurrogateIsEscapedRatherThanLost() {
        assertEquals("\"x\\ud83dy\"", cell(Cell.Text("x\ud83dy")))
    }

    /** A null is JSON null -- never the text "null", and never a missing key. */
    @Test
    fun nullIsWrittenAsJsonNull() {
        assertEquals("null", cell(Cell.Null))
        assertEquals("\"null\"", cell(Cell.Text("null")))
    }

    @Test
    fun integersAreWrittenExactlyAtTheExtremes() {
        assertEquals("9223372036854775807", cell(Cell.Integer(Long.MAX_VALUE)))
        assertEquals("-9223372036854775808", cell(Cell.Integer(Long.MIN_VALUE)))
        // Instants are stored as epoch millis; one must come back as the same number.
        assertEquals("1790388321318", cell(Cell.Integer(1_790_388_321_318)))
    }

    /**
     * A REAL stays a REAL. 3.0 written as `3` would come back from a parser as an integer, and 1.5
     * bottles is the number spec §1 builds its case for attainment on.
     */
    @Test
    fun realsKeepTheirTypeAndTheirValue() {
        assertEquals("3.0", cell(Cell.Real(3.0)))
        assertEquals("1.5", cell(Cell.Real(1.5)))
        assertEquals("0.1", cell(Cell.Real(0.1)))
        assertEquals(0.1, cell(Cell.Real(0.1)).toDouble(), 0.0)
        assertEquals("-2.5E-7", cell(Cell.Real(-2.5e-7)))
    }

    /** JSON has no NaN or infinity. Writing one would produce a file no parser accepts. */
    @Test
    fun aNumberJsonCannotHoldIsRefusedAndNamed() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            write(snapshot(TableData("answers", listOf("value_number"), listOf(listOf(Cell.Real(Double.NaN))))))
        }
        assertTrue(error.message!!, "answers.value_number" in error.message!!)
    }

    /**
     * No column holds a blob. One appearing is a schema change, and how the format carries it is a
     * decision to make then -- not a guess to make silently now.
     */
    @Test
    fun aBlobIsRefusedAndNamed() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            write(snapshot(TableData("items", listOf("icon"), listOf(listOf(Cell.Blob)))))
        }
        assertTrue(error.message!!, "items.icon" in error.message!!)
    }

    /** A row that does not match its columns is a reader bug, and must not become a shifted file. */
    @Test
    fun aRowOfTheWrongWidthIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            write(snapshot(TableData("items", listOf("id", "kind"), listOf(listOf(Cell.Text("meals"))))))
        }
    }

    /**
     * Tables are written in name order whatever order they arrive in, and the same data always gives
     * the same bytes -- so two exports of an unchanged database can be compared with a diff.
     */
    @Test
    fun theOutputIsDeterministic() {
        val a = TableData("answers", listOf("id"), listOf(listOf(Cell.Text("a1"))))
        val b = TableData("checkins", listOf("id"), listOf(listOf(Cell.Text("c1"))))

        val forwards = write(snapshot(a, b))
        assertEquals(forwards, write(snapshot(b, a)))
        assertEquals(forwards, write(snapshot(a, b)))
        assertTrue(forwards.indexOf("\"answers\"") < forwards.indexOf("\"checkins\""))
    }

    /** Rows keep the order they were read in; only tables are sorted. */
    @Test
    fun rowsKeepTheirOrder() {
        val json = write(
            snapshot(TableData("checkins", listOf("id"), listOf(listOf(Cell.Text("z")), listOf(Cell.Text("a"))))),
        )
        assertTrue(json.indexOf("\"z\"") < json.indexOf("\"a\""))
    }
}
