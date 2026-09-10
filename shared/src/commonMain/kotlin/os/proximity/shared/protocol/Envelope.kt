package os.proximity.shared.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import os.proximity.shared.capability.CapabilityAdvertisement
import os.proximity.shared.lists.ListOperation
import os.proximity.shared.lists.SharedList

/**
 * Application-level messages, carried inside an encrypted [FrameType.SEALED]
 * frame once a session is established (handshake envelopes excepted).
 *
 * Envelopes are deserialized from attacker-controlled bytes, so
 * [EnvelopeCodec.decode] never throws — a malformed envelope is a null, not
 * a crash.
 */
@Serializable
sealed class Envelope {

    /**
     * First message of the handshake. Sent unencrypted; the signature binds
     * the ephemeral key to the sender's long-term identity so an observer
     * cannot substitute their own ephemeral key (see docs/THREAT_MODEL.md
     * #2 Capability spoofing).
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val identityPublicKey: String,
        val ephemeralPublicKey: String,
        val nonce: String,
        val signature: String,
        val displayName: String,
        val protocolVersion: Int = PROTOCOL_VERSION
    ) : Envelope()

    /** Response to [Hello], same construction. */
    @Serializable
    @SerialName("hello_ack")
    data class HelloAck(
        val identityPublicKey: String,
        val ephemeralPublicKey: String,
        val nonce: String,
        val signature: String,
        val displayName: String,
        val protocolVersion: Int = PROTOCOL_VERSION
    ) : Envelope()

    /** A chat message in a pairwise conversation. */
    @Serializable
    @SerialName("chat")
    data class Chat(
        val messageId: String,
        val sentAtEpochMillis: Long,
        val body: String
    ) : Envelope()

    /** Delivery confirmation for a previously sent [Chat]. */
    @Serializable
    @SerialName("ack")
    data class Ack(
        val messageId: String
    ) : Envelope()

    /**
     * A single change to a shared list. Sent as an operation rather than a
     * snapshot so two devices that edited while apart merge instead of
     * clobbering each other.
     */
    @Serializable
    @SerialName("list_op")
    data class ListOp(val operation: ListOperation) : Envelope()

    /**
     * Full replicas, exchanged when a session is established. Replaying
     * every operation ever made would not scale, so reconnecting peers
     * swap state and merge it instead.
     */
    @Serializable
    @SerialName("list_sync")
    data class ListSync(val lists: List<SharedList>) : Envelope()

    /**
     * What this device currently offers to do for the peer. A claim about
     * willingness, never a grant of permission — see the capability package.
     */
    @Serializable
    @SerialName("capability")
    data class CapabilityAdvert(
        val advertisement: CapabilityAdvertisement
    ) : Envelope()

    /**
     * Announces a file without sending its bytes yet. Metadata travels
     * first so the receiver's Guardrail Engine can evaluate `RECEIVE_FILE`
     * — and the user can be asked, if policy says so — before the sender
     * commits to transmitting anything.
     */
    @Serializable
    @SerialName("file_offer")
    data class FileOffer(
        val fileId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long
    ) : Envelope()

    @Serializable
    @SerialName("file_accept")
    data class FileAccept(val fileId: String) : Envelope()

    @Serializable
    @SerialName("file_decline")
    data class FileDecline(val fileId: String, val reason: String? = null) : Envelope()

    /** The bytes, sent only after [FileAccept]. Hex-encoded — see
     *  docs/adr/0004-file-transfer.md for why, and the size ceiling it implies. */
    @Serializable
    @SerialName("file_data")
    data class FileData(val fileId: String, val bytesHex: String) : Envelope()

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

object EnvelopeCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "t"
        encodeDefaults = true
    }

    fun encode(envelope: Envelope): ByteArray =
        json.encodeToString(Envelope.serializer(), envelope).encodeToByteArray()

    /** Returns null for anything that isn't a valid envelope. */
    fun decode(bytes: ByteArray): Envelope? = try {
        json.decodeFromString(Envelope.serializer(), bytes.decodeToString())
    } catch (e: Exception) {
        null
    }
}
