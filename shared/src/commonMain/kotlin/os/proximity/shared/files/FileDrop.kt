package os.proximity.shared.files

import kotlinx.serialization.Serializable

@Serializable
enum class FileTransferDirection { SENT, RECEIVED }

@Serializable
enum class FileTransferStatus {
    /** Outbound: sent, waiting for the peer's accept or decline. */
    OFFERED,

    /** Inbound: arrived, waiting for the local user's decision. */
    PENDING,

    /** Accepted; bytes are in flight. */
    TRANSFERRING,

    COMPLETE,
    DECLINED,
    EXPIRED,
    FAILED
}

/**
 * Metadata for one file transfer.
 *
 * The bytes themselves live separately (see [FileDropStore]) so the list a
 * user sees — filenames, sizes, status — can be loaded without pulling
 * every file's contents into memory just to render a list row.
 */
@Serializable
data class FileDrop(
    val id: String,
    val peerDeviceId: String,
    val direction: FileTransferDirection,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: FileTransferStatus,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    /** A transfer in flight is never treated as expired, however old it gets —
     *  it either finishes or fails, but isn't silently deleted mid-transfer. */
    fun isExpired(nowEpochMillis: Long): Boolean =
        status != FileTransferStatus.TRANSFERRING && nowEpochMillis >= expiresAtEpochMillis
}
