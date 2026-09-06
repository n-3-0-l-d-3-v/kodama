package os.proximity.shared.storage

/**
 * Minimal persistence surface.
 *
 * Deliberately small and text-oriented: everything Kodama stores is
 * either a short document or an append-only log, and keeping the platform
 * contract this thin means the storage *logic* (compaction, bounding,
 * recovery from corruption) lives in shared code where it is testable.
 *
 * Implementations must not throw for a missing file — that is the ordinary
 * first-run condition, not an error.
 */
interface FileStore {

    /** Returns null when the file does not exist. */
    suspend fun readText(name: String): String?

    /** Creates or replaces the file atomically enough that a crash mid-write
     *  cannot leave a half-written file readable as valid content. */
    suspend fun writeText(name: String, content: String)

    /** Appends a single line, creating the file if needed. */
    suspend fun appendLine(name: String, line: String)

    suspend fun delete(name: String)
}

/** In-memory [FileStore] for tests. */
class InMemoryFileStore : FileStore {

    private val files = mutableMapOf<String, StringBuilder>()

    override suspend fun readText(name: String): String? = files[name]?.toString()

    override suspend fun writeText(name: String, content: String) {
        files[name] = StringBuilder(content)
    }

    override suspend fun appendLine(name: String, line: String) {
        files.getOrPut(name) { StringBuilder() }.append(line).append('\n')
    }

    override suspend fun delete(name: String) {
        files.remove(name)
    }

    /** Test helper: how many bytes a file currently holds. */
    fun sizeOf(name: String): Int = files[name]?.length ?: 0
}
