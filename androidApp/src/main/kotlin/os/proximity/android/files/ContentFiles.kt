package os.proximity.android.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The small pieces of `ContentResolver` plumbing file transfer needs,
 * isolated here so the UI code that calls them stays about intent, not
 * cursor bookkeeping.
 */
object ContentFiles {

    /** Best-effort display name for a picked document; a generic fallback if unavailable. */
    fun displayNameOf(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { return it }
            }
        }
        return "file"
    }

    fun mimeTypeOf(resolver: ContentResolver, uri: Uri): String =
        resolver.getType(uri) ?: "application/octet-stream"

    /** Reads a picked document's full contents, or null if it couldn't be opened. */
    fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray? =
        resolver.openInputStream(uri)?.use { it.readBytes() }

    /** Writes to a location the user chose via a document-creation picker. */
    fun writeBytes(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
}
