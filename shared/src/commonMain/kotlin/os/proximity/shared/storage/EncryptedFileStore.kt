package os.proximity.shared.storage

import os.proximity.shared.util.hexToBytesOrNull
import os.proximity.shared.util.toHex

/**
 * Wraps a [FileStore], encrypting every value with [cipher] before it
 * reaches the underlying store and decrypting on read. Every other class
 * in `shared` that persists something — the audit log, the trust store,
 * shared lists, capabilities, file drops — is unaware this layer exists;
 * they read and write plain text exactly as before.
 *
 * ### Why encryption is transparent to `writeText` *and* `appendLine`
 *
 * [FileAuditLog] calls `appendLine` once per entry, but also occasionally
 * rewrites the whole file with `writeText` during compaction. Both must
 * decrypt back to exactly the same content whichever path produced them,
 * without this class knowing which one a given caller uses.
 *
 * The trick: every call — `writeText` or `appendLine` — seals its own
 * argument as one independent **record** and writes that record as a
 * single line (hex has no newlines, so this is always safe). `readText`
 * reads the raw file, splits it on `\n`, opens every record, and rejoins
 * the plaintexts with `\n`. A `writeText` of a multi-line string is one
 * record whose *opened plaintext itself* contains the embedded newlines;
 * `appendLine`'s single-line records interleave with that seamlessly. The
 * result is byte-identical to what the same call sequence would have
 * produced against an unencrypted [FileStore].
 *
 * A record that fails to open — tampered, truncated, or sealed under a
 * retired key — is skipped rather than failing the whole read. This
 * preserves [FileAuditLog]'s own "lose one entry, not the whole history"
 * behaviour for corrupt input; a caller that instead expects one
 * all-or-nothing document (the trust store, say) already treats an empty
 * or unparseable result as "start fresh" in its own loader.
 */
class EncryptedFileStore(
    private val delegate: FileStore,
    private val cipher: AtRestCipher
) : FileStore {

    override suspend fun readText(name: String): String? {
        val raw = delegate.readText(name) ?: return null
        val aad = name.encodeToByteArray()

        val plainLines = mutableListOf<String>()
        for (recordHex in raw.lineSequence()) {
            if (recordHex.isBlank()) continue
            val sealed = recordHex.hexToBytesOrNull() ?: continue
            val plaintext = cipher.open(aad, sealed) ?: continue
            plainLines.add(plaintext.decodeToString())
        }

        return if (plainLines.isEmpty()) null else plainLines.joinToString("\n")
    }

    override suspend fun writeText(name: String, content: String) {
        // The trailing newline matters: without it, a later appendLine on
        // this same file would concatenate its record directly onto this
        // one with no separator, producing a single unparseable line.
        // InMemoryFileStore.writeText (and AndroidFileStore.writeText) both
        // store content verbatim with no newline of their own, so it has to
        // be added here.
        delegate.writeText(name, sealRecord(name, content) + "\n")
    }

    override suspend fun appendLine(name: String, line: String) {
        delegate.appendLine(name, sealRecord(name, line))
    }

    override suspend fun delete(name: String) = delegate.delete(name)

    private fun sealRecord(name: String, plaintext: String): String =
        cipher.seal(name.encodeToByteArray(), plaintext.encodeToByteArray()).toHex()
}
