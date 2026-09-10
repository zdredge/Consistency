package com.zdredge.consistency.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.zdredge.consistency.container
import com.zdredge.consistency.data.db.DATABASE_NAME
import com.zdredge.consistency.data.export.SnapshotSummary
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Puts a copy of the database somewhere the user can actually get at it.
 *
 * **Downloads, via `MediaStore`, on purpose.** The app's own external directory would be simpler and
 * is deleted when the app is uninstalled — which is precisely one of the events an export exists to
 * survive. A file in Downloads outlives the app, is visible in the Files app, and can be copied off
 * the phone without a cable.
 *
 * **This is a copy, not a backup.** Nothing reads these files back yet. Until a restore has actually
 * been performed the file is only *believed* to be restorable, and it should never be described to
 * the user as a backup.
 */
object ExportToDownloads {

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    private const val TAG = "Export"

    /**
     * Writes a timestamped snapshot and returns what it contained, or null if it could not be
     * written.
     *
     * Timestamped rather than overwriting one file: an export that silently replaces the previous
     * one turns a single bad export into the loss of every good one before it.
     */
    suspend fun run(context: Context): SnapshotSummary? {
        val app = context.applicationContext
        val name = "consistency-${LocalDateTime.now().format(stamp)}.db"

        val details = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        }

        return try {
            val uri = app.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, details)
                ?: error("MediaStore refused to create $name in ${Environment.DIRECTORY_DOWNLOADS}")

            val summary = app.contentResolver.openOutputStream(uri)?.use { out ->
                app.container.repository.writeSnapshotTo(app.getDatabasePath(DATABASE_NAME), out)
            } ?: error("could not open $uri for writing")

            Log.i(TAG, "exported $name: $summary")
            summary
        } catch (e: Exception) {
            // Reported to the user by returning null. A failed export that looked successful is
            // worse than no export at all, because it is the one you would rely on.
            Log.e(TAG, "export failed", e)
            null
        }
    }
}
