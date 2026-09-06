package os.proximity.shared.crypto

/**
 * An ephemeral ECDH keypair. The private half is held behind an opaque
 * platform handle so it never becomes ordinary bytes in shared code.
 */
class EcdhKeyPair(
    /** X.509 / SubjectPublicKeyInfo encoding, safe to send to a peer. */
    val publicKey: ByteArray,
    internal val privateHandle: Any
)

/**
 * The minimum set of primitives Kodama needs from the platform.
 *
 * This surface is deliberately small. Everything that can be expressed as
 * composition of these primitives — key derivation, the handshake
 * transcript, session key scheduling — lives in shared code where it is
 * unit-testable on every platform, rather than being duplicated per
 * platform and verified nowhere.
 */
interface CryptoPrimitives {

    fun randomBytes(size: Int): ByteArray

    fun sha256(data: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    fun generateEcdhKeyPair(): EcdhKeyPair

    /**
     * Raw ECDH shared secret. Never used as a key directly — always run
     * through [Hkdf] first, per docs/adr/0001-cryptography.md.
     */
    fun ecdhSharedSecret(keyPair: EcdhKeyPair, peerPublicKey: ByteArray): ByteArray?

    /**
     * AES-256-GCM. Returns ciphertext with the authentication tag appended.
     */
    fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray

    /**
     * Reverses [aeadSeal]. Returns null when authentication fails — a
     * forged, replayed, or corrupted frame must be indistinguishable to
     * callers, and must never throw.
     */
    fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray?
}

object CryptoConstants {
    const val AES_KEY_SIZE = 32
    const val GCM_NONCE_SIZE = 12
    const val GCM_TAG_SIZE = 16
}
