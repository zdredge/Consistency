package com.zdredge.consistency.data.export

import java.time.Instant
import java.time.ZoneId

/**
 * One stored value, typed as SQLite holds it -- not as Kotlin would like it to be.
 *
 * The export writes the **stored form**: an enum by its name, a date as ISO-8601 text, an instant as
 * epoch millis (architecture §5). That is what an importer would have to write back, so converting
 * anything on the way out would be a second encoding to keep in step with the first.
 */
sealed interface Cell {
    data object Null : Cell
    data class Integer(val value: Long) : Cell
    data class Real(val value: Double) : Cell
    data class Text(val value: String) : Cell

    /**
     * Carried so the writer can refuse it by name. No column holds a blob; one appearing is a schema
     * change, and how the format carries it is a decision to take then rather than a guess now.
     */
    data object Blob : Cell
}

/** One table as read: its columns in order, and each row's cells in the same order. */
data class TableData(val name: String, val columns: List<String>, val rows: List<List<Cell>>)

/** Everything one export holds. Built by `SnapshotReader`, written by [JsonSnapshot]. */
data class SnapshotData(
    val schemaVersion: Int,
    val identityHash: String,
    val exportedAt: Instant,
    val zone: ZoneId,
    val tables: List<TableData>,
)

/**
 * Writes a full-database snapshot as JSON (spec §2, build-order M11).
 *
 * **Hand-written rather than a library, deliberately.** What is needed is a writer for five value
 * types, and the one part worth getting exactly right -- escaping -- is a dozen lines that can be
 * pinned by tests. A serialisation library would be a new dependency (ask-first) for less control
 * over the one thing that matters.
 *
 * **Deterministic.** Tables in name order, rows in the order they were read, keys in column order,
 * fixed layout -- so two exports of an unchanged database are identical and can be compared with a
 * diff. One row per line keeps a file of thousands of rows readable in an editor.
 *
 * **It refuses rather than guesses.** A NaN has no JSON form, a blob has no agreed one, and a row that
 * does not match its columns is a reader bug; each throws, naming the table and column, because a
 * file that silently dropped or shifted a value is worse than an export that says it failed.
 */
object JsonSnapshot {

    /** Identifies the file. Bumped only if the shape changes in a way a reader must know about. */
    const val FORMAT = "consistency-export"
    const val FORMAT_VERSION = 1

    /** Written into every file, so the stored forms can be read without this codebase to hand. */
    const val ENCODING =
        "values as stored: enums by name, dates and times ISO-8601 text, instants epoch milliseconds"

    fun write(data: SnapshotData, out: Appendable) {
        out.append("{\n")
        out.field("format", string(FORMAT))
        out.field("formatVersion", FORMAT_VERSION.toString())
        out.field("exportedAt", string(data.exportedAt.toString()))
        out.field("zone", string(data.zone.id))
        out.field("schemaVersion", data.schemaVersion.toString())
        out.field("identityHash", string(data.identityHash))
        out.field("encoding", string(ENCODING))
        out.append("  \"tables\": {")

        val tables = data.tables.sortedBy { it.name }
        tables.forEachIndexed { index, table ->
            out.append(if (index == 0) "\n" else ",\n")
            writeTable(table, out)
        }
        out.append(if (tables.isEmpty()) "}\n" else "\n  }\n")
        out.append("}\n")
    }

    private fun writeTable(table: TableData, out: Appendable) {
        out.append("    ").append(string(table.name)).append(": {\n")
        out.append("      \"rowCount\": ").append(table.rows.size.toString()).append(",\n")

        if (table.rows.isEmpty()) {
            out.append("      \"rows\": []\n")
        } else {
            out.append("      \"rows\": [\n")
            table.rows.forEachIndexed { index, row ->
                require(row.size == table.columns.size) {
                    "${table.name}: a row has ${row.size} cells for ${table.columns.size} columns"
                }
                out.append("        {")
                row.forEachIndexed { i, cell ->
                    if (i > 0) out.append(", ")
                    val column = table.columns[i]
                    out.append(string(column)).append(": ").append(value(cell, "${table.name}.$column"))
                }
                out.append(if (index < table.rows.lastIndex) "},\n" else "}\n")
            }
            out.append("      ]\n")
        }
        out.append("    }")
    }

    private fun Appendable.field(name: String, json: String) {
        append("  ").append(string(name)).append(": ").append(json).append(",\n")
    }

    private fun value(cell: Cell, where: String): String = when (cell) {
        Cell.Null -> "null"
        is Cell.Integer -> cell.value.toString()
        is Cell.Real -> {
            require(cell.value.isFinite()) { "$where holds ${cell.value}, which JSON cannot represent" }
            // Kotlin's shortest round-trip form, which keeps a REAL visibly a REAL: 3.0, not 3.
            // Its exponent form (2.5E-7) is valid JSON as it stands.
            cell.value.toString()
        }
        is Cell.Text -> string(cell.value)
        Cell.Blob -> throw IllegalArgumentException("$where holds a blob; the export format has no form for one")
    }

    /**
     * A JSON string. Escapes exactly what JSON requires -- the quote, the backslash and control
     * characters -- plus any lone surrogate, which UTF-8 cannot encode and would otherwise leave the
     * phone as `?`. Everything else, accents and emoji included, is written as itself.
     */
    private fun string(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c < ' ' -> out.append(unicodeEscape(c))
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    out.append(c).append(value[i + 1])
                    i++
                }
                c.isSurrogate() -> out.append(unicodeEscape(c))
                else -> out.append(c)
            }
            i++
        }
        return out.append('"').toString()
    }

    // Not String.format: that follows the default locale, and some locales write digits in another
    // script -- an export taken on such a phone would carry escapes no parser reads.
    private fun unicodeEscape(c: Char) = "\\u" + c.code.toString(16).padStart(4, '0')
}
