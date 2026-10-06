package com.zdredge.consistency.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.zdredge.consistency.container
import com.zdredge.consistency.data.export.SnapshotSummary
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The full-database JSON export (spec §2, build-order M11), written to a document the user chose
 * through the system picker (`ACTION_CREATE_DOCUMENT`).
 *
 * **The picker is the whole destination story.** Drive, Downloads, a USB stick -- whatever the system
 * sheet offers. There is no Drive API, no account and no cloud dependency anywhere in the build
 * (`CLAUDE.md`, *Export — decided*), and that is deliberate rather than a gap.
 *
 * The raw `.db` copy (`ExportToDownloads`) stays beside this one: until an importer exists it is the
 * file that can be dropped straight back in.
 */
object ExportToDocument {

    private const val TAG = "Export"

    const val MIME_TYPE = "application/json"

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    /**
     * The name the picker suggests, from the injected clock in its own zone -- so the fixture install,
     * or a test clock, names the file for the moment it believes it is.
     */
    fun suggestedName(context: Context): String {
        val resolver = context.applicationContext.container.dayResolver
        return "consistency-${LocalDateTime.ofInstant(resolver.now(), resolver.zone).format(stamp)}.json"
    }

    sealed interface Outcome {
        data class Saved(val name: String?, val summary: SnapshotSummary) : Outcome

        /** The write failed and the document it started was removed, so nothing half-written remains. */
        data object FailedAndRemoved : Outcome

        /**
         * The write failed and the document could not be removed. Said separately, because "nothing
         * was saved" would be false: there is a file at the chosen place, and it is incomplete.
         */
        data object FailedAndLeftBehind : Outcome
    }

    suspend fun run(context: Context, uri: Uri): Outcome {
        val app = context.applicationContext
        return try {
            val summary = app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                app.container.repository.writeJsonExportTo(out)
            } ?: error("could not open $uri for writing")

            Log.i(TAG, "exported JSON to $uri: $summary")
            Outcome.Saved(displayName(app, uri), summary)
        } catch (e: Exception) {
            Log.e(TAG, "JSON export failed", e)
            // An incomplete file at the place the user chose is the export they would later rely on
            // and find wanting. Remove it if the provider allows.
            val removed = runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                .getOrDefault(false)
            if (removed) Outcome.FailedAndRemoved else Outcome.FailedAndLeftBehind
        }
    }

    /** What the provider calls the file, which may differ from the suggestion if the user renamed it. */
    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}
