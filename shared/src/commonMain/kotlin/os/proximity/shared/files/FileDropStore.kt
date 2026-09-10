package os.proximity.shared.files

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import os.proximity.shared.storage.FileStore
import os.proximity.shared.util.hexToBytesOrNull
import os.proximity.shared.util.toHex

/**
 * Persists file transfer metadata and bytes.
 *
 * Kept as two separate concerns because they have different lifetimes and
 * different reasons to be read: the manifest is small and read on every
 * launch to render a list; a file's bytes are large and read only when the
 * user actually asks to save one.
 */
class FileDropStore(
    private val files: FileStore,
    private val manifestFileName: String = DEFAULT_MANIFEST_NAME
) {

    suspend fun loadManifest(): List<FileDrop> {
        val raw = files.readText(manifestFileName) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(FileDrop.serializer()), raw)
        } catch (e: Exception) {
            // A corrupt manifest loses the file list, not anything security
            // sensitive. Start empty rather than refusing to launch.
            emptyList()
        }
    }

    suspend fun saveManifest(drops: List<FileDrop>) {
        files.writeText(manifestFileName, json.encodeToString(ListSerializer(FileDrop.serializer()), drops))
    }

    suspend fun saveBytes(fileId: String, bytes: ByteArray) {
        files.writeText(bytesFileName(fileId), bytes.toHex())
    }

    suspend fun loadBytes(fileId: String): ByteArray? =
        files.readText(bytesFileName(fileId))?.hexToBytesOrNull()

    suspend fun deleteBytes(fileId: String) {
        files.delete(bytesFileName(fileId))
    }

    private fun bytesFileName(fileId: String) = "file-$fileId.hex"

    companion object {
        const val DEFAULT_MANIFEST_NAME = "file-drops.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
