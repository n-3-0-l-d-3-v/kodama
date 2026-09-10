package os.proximity.shared.identity

import os.proximity.shared.util.hexToBytesOrNull
import os.proximity.shared.util.toHex

/**
 * Encodes an identity public key into (and back out of) a short text
 * payload suitable for a QR code.
 *
 * This exists because manual fingerprint comparison — reading a code aloud
 * and checking it matches — is the kind of step real people skip, or get
 * wrong under time pressure. Scanning a QR code happens over an optical,
 * physically-in-person channel that the mesh's documented first-contact gap
 * (docs/adr/0001-cryptography.md, docs/THREAT_MODEL.md #9) cannot reach: an
 * attacker relaying BLE traffic between two victims has no way to also
 * intercept what is printed on someone's screen. Scanning the code and
 * matching it against an already-established session's device ID is a
 * genuine fix for that gap, not just a convenience.
 *
 * No signature is included. The security property comes from the scan
 * itself happening in person over a channel the network attacker cannot
 * touch, not from anything cryptographic layered on top of the payload.
 */
object QrVerificationCodec {

    private const val PREFIX = "kodama:1:"

    fun encode(identityPublicKeyBytes: ByteArray): String = PREFIX + identityPublicKeyBytes.toHex()

    /**
     * Returns null for anything that isn't a well-formed payload. This
     * parses whatever a camera happened to point at, so it must never
     * throw — a QR code aimed at a poster, a URL, or random noise is an
     * ordinary "not ours" result, not an error.
     */
    fun decode(payload: String): ByteArray? {
        if (!payload.startsWith(PREFIX)) return null
        val hex = payload.removePrefix(PREFIX)
        if (hex.isEmpty()) return null
        return hex.hexToBytesOrNull()
    }
}
