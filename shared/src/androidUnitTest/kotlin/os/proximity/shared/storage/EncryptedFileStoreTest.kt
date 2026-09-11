package os.proximity.shared.storage

import kotlinx.coroutines.test.runTest
import os.proximity.shared.crypto.AndroidCryptoPrimitives
import os.proximity.shared.crypto.CryptoConstants
import os.proximity.shared.guardrail.AuditLogEntry
import os.proximity.shared.guardrail.FileAuditLog
import os.proximity.shared.guardrail.GuardrailRequest
import os.proximity.shared.guardrail.RequestDirection
import os.proximity.shared.guardrail.ActionType
import os.proximity.shared.identity.FileTrustStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A plain-JVM AES-GCM cipher standing in for the real Keystore-backed one,
 * the same way [os.proximity.shared.session.HandshakeTest]'s `TestIdentity`
 * stands in for [os.proximity.shared.identity.KeystoreDeviceIdentityProvider] —
 * Android Keystore itself is unavailable outside a real device or emulator.
 */
private class FakeAtRestCipher(
    private val primitives: AndroidCryptoPrimitives = AndroidCryptoPrimitives(),
    private val key: ByteArray = AndroidCryptoPrimitives().randomBytes(CryptoConstants.AES_KEY_SIZE)
) : AtRestCipher {

    override fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = primitives.randomBytes(CryptoConstants.GCM_NONCE_SIZE)
        val ciphertext = primitives.aeadSeal(key, nonce, plaintext, aad)
        return nonce + ciphertext
    }

    override fun open(aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < CryptoConstants.GCM_NONCE_SIZE) return null
        val nonce = sealed.copyOfRange(0, CryptoConstants.GCM_NONCE_SIZE)
        val ciphertext = sealed.copyOfRange(CryptoConstants.GCM_NONCE_SIZE, sealed.size)
        return primitives.aeadOpen(key, nonce, ciphertext, aad)
    }
}

class EncryptedFileStoreTest {

    private fun store(delegate: InMemoryFileStore = InMemoryFileStore(), cipher: AtRestCipher = FakeAtRestCipher()) =
        EncryptedFileStore(delegate, cipher)

    @Test
    fun writeThenReadRoundTrips() = runTest {
        val store = store()
        store.writeText("doc.json", """{"hello":"world"}""")
        assertEquals("""{"hello":"world"}""", store.readText("doc.json"))
    }

    @Test
    fun writtenContentIsNotStoredInTheClear() = runTest {
        val delegate = InMemoryFileStore()
        val store = store(delegate)
        store.writeText("secret.json", "verified-peer-device-id-12345")

        val onDisk = delegate.readText("secret.json")
        assertTrue(onDisk != null && "verified-peer-device-id-12345" !in onDisk)
    }

    @Test
    fun multiLineContentSurvivesAsOneRecord() = runTest {
        val store = store()
        val multiLine = "line one\nline two\nline three"
        store.writeText("doc.txt", multiLine)
        assertEquals(multiLine, store.readText("doc.txt"))
    }

    @Test
    fun appendedLinesRoundTripInOrder() = runTest {
        val store = store()
        store.appendLine("log.jsonl", "{\"n\":1}")
        store.appendLine("log.jsonl", "{\"n\":2}")
        store.appendLine("log.jsonl", "{\"n\":3}")

        val restored = store.readText("log.jsonl")
        assertEquals(listOf("{\"n\":1}", "{\"n\":2}", "{\"n\":3}"), restored?.lines())
    }

    @Test
    fun writeThenAppendMatchesFileAuditLogsCompactThenContinuePattern() = runTest {
        // Mirrors FileAuditLog.compact(): a writeText of several joined
        // lines, immediately followed by more appendLine calls.
        val store = store()
        store.writeText("log.jsonl", "{\"n\":1}\n{\"n\":2}\n")
        store.appendLine("log.jsonl", "{\"n\":3}")
        store.appendLine("log.jsonl", "{\"n\":4}")

        val restored = store.readText("log.jsonl")
        assertEquals("{\"n\":1}\n{\"n\":2}\n\n{\"n\":3}\n{\"n\":4}", restored)
    }

    @Test
    fun emptyContentStillRoundTrips() = runTest {
        val store = store()
        store.writeText("empty.txt", "")
        assertEquals("", store.readText("empty.txt"))
    }

    @Test
    fun readingAMissingFileReturnsNull() = runTest {
        assertNull(store().readText("never-written.json"))
    }

    @Test
    fun aTamperedRecordIsSkippedNotFatal() = runTest {
        val delegate = InMemoryFileStore()
        val store = store(delegate)
        store.appendLine("log.jsonl", "{\"n\":1}")
        store.appendLine("log.jsonl", "{\"n\":2}")

        // Corrupt the on-disk (well, in-memory) ciphertext for the whole file
        // by flipping a character partway through — simulating bit rot or a
        // torn write, the same fault FileAuditLog's own tests inject.
        val raw = requireNotNull(delegate.readText("log.jsonl"))
        val lines = raw.lines().toMutableList()
        lines[0] = lines[0].dropLast(2) + "00" // flip the tail of the first record
        delegate.writeText("log.jsonl", lines.joinToString("\n"))

        val restored = store.readText("log.jsonl")
        // The corrupted record is gone; the untouched one survives.
        assertEquals("{\"n\":2}", restored)
    }

    @Test
    fun aWrongKeyCannotDecryptAnything() = runTest {
        val delegate = InMemoryFileStore()
        store(delegate, FakeAtRestCipher()).writeText("doc.json", "top secret")

        val wrongKeyStore = store(delegate, FakeAtRestCipher())
        assertNull(wrongKeyStore.readText("doc.json"))
    }

    @Test
    fun fileNameIsBoundAsAssociatedData() = runTest {
        // A ciphertext sealed for one file name must not open under another —
        // otherwise an attacker who can move files around (or a bug that
        // mixes up file names) could pass off one file's contents as another's.
        val delegate = InMemoryFileStore()
        val cipher = FakeAtRestCipher()
        store(delegate, cipher).writeText("a.json", "belongs to a")

        val sealedForA = requireNotNull(delegate.readText("a.json"))
        delegate.writeText("b.json", sealedForA)

        val store = store(delegate, cipher)
        assertNull(store.readText("b.json"), "a record sealed for a.json must not open as b.json")
        assertEquals("belongs to a", store.readText("a.json"))
    }

    @Test
    fun deleteRemovesTheUnderlyingFile() = runTest {
        val delegate = InMemoryFileStore()
        val store = store(delegate)
        store.writeText("doc.json", "content")

        store.delete("doc.json")

        assertNull(store.readText("doc.json"))
        assertEquals(0, delegate.sizeOf("doc.json"))
    }

    // ------------------------------------------------- composed with real callers

    @Test
    fun trustStoreWorksTransparentlyWhenWrappedInEncryption() = runTest {
        val delegate = InMemoryFileStore()
        val encrypted = store(delegate)

        val original = FileTrustStore(encrypted)
        original.markVerified("device-a")

        val reloaded = FileTrustStore(encrypted).also { it.load() }
        assertEquals(
            os.proximity.shared.guardrail.TrustState.VERIFIED,
            reloaded.trustStateOf("device-a")
        )

        // And it is genuinely encrypted underneath, not just passed through.
        val onDisk = delegate.readText(FileTrustStore.DEFAULT_FILE_NAME)
        assertTrue(onDisk != null && "device-a" !in onDisk)
    }

    @Test
    fun auditLogWorksTransparentlyWhenWrappedInEncryption() = runTest {
        val delegate = InMemoryFileStore()
        val encrypted = store(delegate)

        val original = FileAuditLog(encrypted)
        original.append(
            AuditLogEntry(
                timestampEpochMillis = 1_000,
                request = GuardrailRequest(RequestDirection.OUTBOUND, ActionType.DISCOVER_PEER),
                decision = AuditLogEntry.DecisionOutcome.ALLOW,
                reason = "test reason"
            )
        )

        val reloaded = FileAuditLog(encrypted).also { it.load() }
        assertEquals("test reason", reloaded.recent().single().reason)

        val onDisk = delegate.readText(FileAuditLog.DEFAULT_FILE_NAME)
        assertTrue(onDisk != null && "test reason" !in onDisk)
    }
}
