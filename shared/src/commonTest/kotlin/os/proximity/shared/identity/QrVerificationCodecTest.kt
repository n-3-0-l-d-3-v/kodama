package os.proximity.shared.identity

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrVerificationCodecTest {

    @Test
    fun encodeThenDecodeRoundTrips() {
        val random = Random(42)
        repeat(20) { size ->
            val bytes = ByteArray(size + 1) { random.nextInt().toByte() }
            val decoded = QrVerificationCodec.decode(QrVerificationCodec.encode(bytes))
            assertTrue(bytes.contentEquals(decoded), "round trip failed for size ${bytes.size}")
        }
    }

    @Test
    fun decodeRejectsMissingPrefix() {
        assertNull(QrVerificationCodec.decode("not-a-kodama-code"))
        assertNull(QrVerificationCodec.decode(""))
        assertNull(QrVerificationCodec.decode("https://example.com"))
    }

    @Test
    fun decodeRejectsMalformedHexAfterPrefix() {
        assertNull(QrVerificationCodec.decode("kodama:1:"))
        assertNull(QrVerificationCodec.decode("kodama:1:zz"))
        assertNull(QrVerificationCodec.decode("kodama:1:abc")) // odd length
    }

    @Test
    fun decodeAcceptsUppercaseHex() {
        val bytes = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
        assertTrue(bytes.contentEquals(QrVerificationCodec.decode("kodama:1:ABCD")))
    }

    @Test
    fun decodeNeverThrowsOnArbitraryInput() {
        // A camera can point at anything: a poster, someone else's app's QR
        // code, static. Parsing must degrade to null, never an exception.
        val random = Random(7)
        repeat(300) {
            val garbage = buildString {
                repeat(random.nextInt(0, 40)) { append(random.nextInt(0, 0x10FFFF).toChar()) }
            }
            QrVerificationCodec.decode(garbage) // must not throw
        }
        assertTrue(true)
    }

    @Test
    fun differentKeysProduceDifferentPayloads() {
        val a = QrVerificationCodec.encode(byteArrayOf(1, 2, 3))
        val b = QrVerificationCodec.encode(byteArrayOf(1, 2, 4))
        assertTrue(a != b)
    }

    @Test
    fun payloadIsHumanRecognisableAsAKodamaCode() {
        // Not load-bearing for security, but worth pinning: someone
        // inspecting a QR payload manually should see what app it's from.
        assertEquals("kodama:1:0102ff", QrVerificationCodec.encode(byteArrayOf(1, 2, -1)))
    }
}
