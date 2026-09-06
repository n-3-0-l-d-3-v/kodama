package os.proximity.shared.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JCA-backed [CryptoPrimitives].
 *
 * Curve choice is P-256 rather than X25519: X25519 only reached the Android
 * platform providers in API 33, and Kodama targets API 26+. P-256
 * ECDH has been available and hardware-accelerated for far longer. See
 * docs/adr/0001-cryptography.md.
 */
class AndroidCryptoPrimitives : CryptoPrimitives {

    private val secureRandom = SecureRandom()

    override fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { secureRandom.nextBytes(it) }

    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An all-zero key is legal for HMAC and is what HKDF-Extract uses as
        // its default salt, but SecretKeySpec rejects an empty array.
        val keyMaterial = if (key.isEmpty()) ByteArray(32) else key
        mac.init(SecretKeySpec(keyMaterial, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun generateEcdhKeyPair(): EcdhKeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val keyPair = generator.generateKeyPair()
        return EcdhKeyPair(
            publicKey = keyPair.public.encoded,
            privateHandle = keyPair.private
        )
    }

    override fun ecdhSharedSecret(keyPair: EcdhKeyPair, peerPublicKey: ByteArray): ByteArray? = try {
        val peerKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(peerPublicKey))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.privateHandle as java.security.PrivateKey)
        agreement.doPhase(peerKey, true)
        agreement.generateSecret()
    } catch (e: Exception) {
        // A peer can send arbitrary bytes as a "public key"; a bad point or
        // a wrong curve must read as a failed handshake, not a crash.
        null
    }

    override fun aeadSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE * 8, nonce)
        )
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(plaintext)
    }

    override fun aeadOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray?
    ): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(CryptoConstants.GCM_TAG_SIZE * 8, nonce)
        )
        aad?.let { cipher.updateAAD(it) }
        cipher.doFinal(ciphertext)
    } catch (e: Exception) {
        // AEADBadTagException and friends: authentication failed.
        null
    }
}
