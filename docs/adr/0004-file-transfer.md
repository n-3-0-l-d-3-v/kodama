# ADR 0004 — File transfer

Status: accepted
Date: 2026-09-10

## Context

The original spec calls for a "temporary secure file drop with automatic
expiry." Two design questions had to be settled before writing any code:
how a file's bytes actually cross the wire, and what "temporary" means in
terms of an enforceable limit.

## Decision

### Reuse the existing message pipeline; don't build a second one

A file is sent as an `Envelope.FileData` carrying its bytes hex-encoded,
routed through the same `SecureSession` → `FrameChunker` →
`MessageAssembler` pipeline that already carries chat messages and list
operations. No new chunking, no new reassembly, no new encryption path.

The alternative — a dedicated streaming transfer with its own
chunk-and-resume protocol — is the more "correct" design for large files,
but it is also a second wire protocol to secure, test, and reason about
right after finishing the first one. Reusing the proven pipeline means file
transfer inherits every property already established for it: replay
rejection, tamper detection, bounded reassembly memory, hostile-input
safety. The cost is a hard ceiling on file size (below).

### Handshake before bytes: offer, then accept, then data

`FileOffer` (metadata only) is sent first. The bytes (`FileData`) are sent
only after `FileAccept` comes back. This exists so the Guardrail Engine —
and the human, if policy says to ask — decides whether to receive a file
*before* the sender has transmitted anything, not after. It also means an
outbound offer that is declined never touches the sender's own disk beyond
holding the bytes in memory (see `FileTransferManager`), and never touches
the receiver's disk at all.

### The size ceiling: 200 KB

Hex encoding doubles byte count. JSON structure and the AEAD tag add a
little more. `Frame.MAX_MESSAGE_BYTES` (512 KiB) is the hard ceiling the
protocol layer already enforces for any single message, chat included.
200 KB of file leaves comfortable headroom under that after inflation,
without every caller needing to do the arithmetic — `FileTransferLimits`
does it once.

This rules out photos from a modern camera and any video. It comfortably
covers documents, screenshots, small images, and text/config files, which
covers what "drop a file to the person next to you" usually means in
practice. Raising the ceiling later means either accepting the larger
message-size cost everywhere (chat and lists too, since they share the
cap) or giving files their own larger cap and streaming path — a
deliberate future decision, not a default to reach for now.

### Automatic expiry, not automatic deletion mid-transfer

Every `FileDrop` carries an `expiresAtEpochMillis`, checked by
`FileDrop.isExpired()`. A transfer whose status is `TRANSFERRING` is never
treated as expired, however old its timestamp gets — it either completes
or fails, but is never silently deleted out from under an in-flight write.
Expiry is swept on load and via an explicit `purgeExpired()` call, mirroring
the pattern already used for stale partial messages in `MessageAssembler`.

### Sent files are not kept

Once `onFileSent` fires, the sender's in-memory copy of the bytes is
discarded — nothing is written to the sender's disk for an outbound
transfer. Only the *receiver* persists bytes, via `FileDropStore`. This
was a deliberate scope cut: "temporary" is taken literally on the sending
side, and a sender who wants their own copy still has the original file
wherever they picked it from.

## Consequences

- No file over 200 KB can be sent. This is the single biggest practical
  limitation of the feature as shipped.
- File transfer travels one hop only, same as chat and lists — there is no
  relay yet for any traffic type, and this ADR does not change that.
- Reusing the chat/list pipeline means file transfer is covered by the same
  hostile-input guarantees (fuzzed frame decoding, bounded reassembly) with
  no new adversarial surface to separately audit.
- Saving a received file and picking one to send both go through Android's
  Storage Access Framework (`ActivityResultContracts.CreateDocument` /
  `GetContent`) rather than direct filesystem paths — standard, but not
  exercised on a device as part of this change.
