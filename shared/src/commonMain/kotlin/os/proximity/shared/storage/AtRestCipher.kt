package os.proximity.shared.storage

/**
 * Seals and opens data for at-rest storage.
 *
 * Deliberately opaque rather than a raw-bytes key parameter — for the same
 * reason `EcdhKeyPair` hides its private key behind a handle
 * (`os.proximity.shared.crypto.EcdhKeyPair.privateHandle: Any`): the real
 * implementation should be backed by a non-extractable platform key
 * (Android Keystore), and a raw-bytes key parameter would defeat that by
 * requiring the key to exist in plain form somewhere just to call this
 * interface.
 */
interface AtRestCipher {

    /** Returns nonce‖ciphertext‖tag as one array. */
    fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * Returns null if [sealed] fails to authenticate — tampered, truncated,
     * or sealed under a different key or [aad]. Never throws: this may be
     * asked to open data left over from a previous key, a previous app
     * version, or outright corruption, none of which are exceptional.
     */
    fun open(aad: ByteArray, sealed: ByteArray): ByteArray?
}
