package os.proximity.shared.files

/**
 * A file crosses the mesh as one JSON envelope, hex-encoded, inside an
 * already-encrypted, already-chunked message — the same pipeline chat
 * messages and list operations use (see docs/adr/0004-file-transfer.md).
 *
 * Hex encoding doubles the byte count, and JSON/AEAD add a little more on
 * top. [MAX_FILE_BYTES] leaves comfortable headroom under
 * `Frame.MAX_MESSAGE_BYTES` (512 KiB) for that inflation, rather than
 * requiring every caller to do the arithmetic themselves.
 */
object FileTransferLimits {
    const val MAX_FILE_BYTES: Long = 200_000L

    /** How long a transfer is kept before it is purged automatically. */
    const val DEFAULT_TTL_MILLIS: Long = 24L * 60 * 60 * 1000
}
