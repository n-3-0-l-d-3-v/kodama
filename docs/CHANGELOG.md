# Changelog

High-level, human-readable log of notable changes. Not every commit is
listed here — this tracks meaningful progress, not every file touched.

## Phase 0 — Foundation

- Initialized repository, README, and `.gitignore`.
- Added architecture overview, threat model skeleton, and Guardrail policy
  design.
- Scaffolded Kotlin Multiplatform + Compose Multiplatform Gradle project
  (`shared`, `androidApp`).
- Added the initial `GuardrailEngine` interface and request/decision model.
- Verified the scaffold builds and runs on an Android emulator.

## Phase 1 — Core Connectivity

**Protocol**

- Wire framing with a 12-byte header, chunking to the negotiated MTU, and
  reassembly that bounds memory, evicts stale partials, ignores duplicate
  chunks, and never throws on malformed input.
- `Envelope` message types (handshake, chat, ack, list operations,
  capability adverts) with a codec that returns null rather than throwing
  on hostile input.

**Cryptography**

- `CryptoPrimitives` — a deliberately small platform surface (RNG,
  SHA-256, HMAC, ECDH, AES-GCM), implemented on Android with JCA.
- HKDF-SHA256 implemented in shared code and checked against RFC 5869
  test vectors.
- `SecureSession`: per-direction keys, monotonic nonces, replay rejection,
  and session-bound associated data.
- Authenticated handshake binding ephemeral keys to long-term identities,
  with length-prefixed transcripts.
- **Replaced Tink with Android Keystore P-256 ECDSA** for device identity,
  so the private key is non-extractable. Rationale in
  `docs/adr/0001-cryptography.md`.

**Guardrail Engine**

- `DefaultGuardrailEngine`: hard-coded safety floor, user-configurable
  rules by priority, category defaults, default-deny fallback.
- `PolicyCatalog` — every user-facing switch in one place, with the plain
  language explanation living next to the rule it produces.
- Audit log made observable so the UI reflects decisions live.

**Mesh**

- `MeshManager`: the single path between the radio and the app. Discovery,
  connection, handshake, encryption, routing, and trust all pass through
  it, and every step consults the Guardrail Engine.
- "Ask me" decisions are surfaced to the UI and genuinely block the mesh
  until the user answers.
- Bluetooth LE transport rewritten with serialised writes (BLE permits one
  outstanding write per connection — the previous version silently dropped
  chunks) and MTU negotiation.

**UI**

- Design system: palette, type scale, shapes, and distinct semantic colours
  for allow / ask / deny.
- Onboarding that states plainly what the app will never do.
- Nearby, Chat, Activity (audit log), and Rules screens.

**Tests**

- Framing, chunking, and hostile-input handling.
- HKDF against published vectors; AEAD tamper, wrong-key, and wrong-AAD
  detection.
- Full handshake including machine-in-the-middle, replay, reflection, and
  malformed-input rejection.
- Two `MeshManager`s over an in-memory transport: handshake, chunked chat,
  delivery acks, the "ask me" flow, and audit coverage.

### Known gaps at the end of this phase

- Conversation history is not persisted; the audit log and trust decisions
  now survive restarts, chat messages do not.
- Messages travel one hop only — no relay or store-and-forward.
- Never run between two physical phones; BLE behaviour is unproven.
- No foreground service, so the mesh stops when the app is backgrounded.

## Phase 3 — Practical Features (in progress)

**Shared lists**

- Lists replicate as an operation-based CRDT with last-writer-wins
  registers per field, so concurrent edits to different fields both
  survive. Ordering uses Lamport counters rather than wall-clock time, so a
  device with a wrong clock cannot dominate every conflict. Rationale in
  `docs/adr/0003-shared-lists.md`.
- Deletion is a tombstone, so a peer that missed a delete cannot resurrect
  the item.
- Live edits broadcast as operations; full replicas are exchanged on
  connect so devices that were apart reconcile.
- Convergence is tested as a property over shuffled, duplicated and
  reversed operation streams. This found two real bugs:
  - `ADD` advanced `doneStamp` without writing a `done` value, which
    suppressed earlier edits depending on arrival order.
  - `createdAtEpochMillis` came from whichever operation first materialised
    an item, so replicas sorted the same list differently.
- New `SYNC_LIST` action type, mediated by the Guardrail Engine in both
  directions, plus a user policy to restrict list sharing to verified peers.
- Lists tab with list overview and item detail screens.

**Mesh robustness** (both found by the list integration tests)

- Sealed frames arriving before the local session is ready are now buffered
  and replayed. The responder completes its handshake first and could send
  application data while the initiator was still deriving keys; those
  frames were being dropped permanently.
- Handshake role is now determined by envelope type rather than local
  state. A peer that dropped and reconnected sends a fresh `Hello`, which
  was previously mistaken for a reply to our own in-flight handshake,
  leaving both sides stuck.

**Capabilities**

- Devices advertise short-lived capabilities describing what they are
  willing to be asked for. A capability is a **claim, not a grant**:
  advertising "I accept files" confers nothing on the asker, and every
  actual request still goes through the Guardrail Engine.
- Only capabilities the user explicitly enabled are ever advertised.
- Advertisements expire, so a device that walks away stops appearing to
  offer things rather than lingering in everyone's list.
- Peer-supplied capability names are filtered against a known catalog, so a
  peer cannot put arbitrary text in front of the user, and peer-supplied
  expiry times are capped.
- Capability toggles added to the Rules screen.

**Persistence**

- Conversation history now survives restarts, trimmed to a bounded number
  of messages per peer. Restored conversations are always marked offline —
  reachability is a fact about right now, not something to restore from a
  file.

**Background operation**

- The object graph moved from the Activity to the Application, so the mesh
  can outlive any single screen. The service and the UI share one
  `MeshManager` — two would mean two identities on the air and two
  conflicting views of the same peers.
- Added an **opt-in** foreground service. Android would allow starting it
  automatically, but an app whose pitch is "default deny, and you can see
  everything it does" should not quietly hold the Bluetooth radio open. The
  user turns it on, and the notification says plainly what is happening.
- The service uses `START_NOT_STICKY`: if the system kills it, staying
  stopped is more honest than silently resuming the radios.
- On Android 13+, notification permission is requested when the user opts
  in, because that notification *is* the disclosure that the radio is
  running.

## Renamed: Proximity OS → Kodama

- Project renamed to **Kodama** (木霊) — Japanese for both a spirit said to
  answer back from within a tree, and the ordinary word for *echo*. The
  mesh forms only where devices are close enough to hear each other and
  disappears when they're not, which is the whole naming rationale in one
  word.
- GitHub repository renamed to `kodama`, with a description added.
- User-facing name updated throughout the README, docs, and in-app strings
  (onboarding, app bar, notification).
- Gradle root project renamed to `kodama`.
- **Not renamed yet**: Kotlin package names (`os.proximity.*`), the
  `os.proximity.android` application ID, and directory paths. That's a
  larger, purely mechanical change touching ~50 files across a module I
  cannot fully compile-verify here, so it's deferred as a deliberate,
  separately-reviewed follow-up rather than done blind.

## QR-code verification

- `QrVerificationCodec` (shared, fully tested): encodes an identity public
  key as `kodama:1:<hex>`, decodes it back, and is fuzz-tested against
  arbitrary camera input — a QR aimed at a poster or someone else's app
  must degrade to "not ours," never an exception.
- Scanning from an open conversation cross-checks the scanned key against
  that conversation's device ID and refuses to verify on a mismatch, rather
  than trusting whatever the camera saw.
- Scanning from the Nearby screen with no conversation open pre-authorises
  whoever the key belongs to — the in-person optical scan is itself the
  verification (docs/THREAT_MODEL.md #9), closing the gap that manual
  fingerprint reading left open by being easy to skip or get wrong.
- First external dependency added to the Android app: ZXing, for QR
  generation and scanning. Confined to two small files
  (`verification/QrCode.kt` for rendering, the scan launcher in
  `ProximityApp.kt`); everything else works with plain strings through the
  shared codec.
- Not yet exercised on a device: camera permission, the third-party scanner
  activity, and the end-to-end scan UI.

## File transfer

- Activates the `SEND_FILE`/`RECEIVE_FILE` action types and the
  "only accept files from people I've verified" policy that have existed
  since Phase 1 but were never wired to anything real.
- Offer → accept/decline → data handshake, reusing the existing encrypted,
  chunked message pipeline rather than building a second one. Rationale
  (including the resulting 200 KB size ceiling) in
  `docs/adr/0004-file-transfer.md`.
- Metadata travels before bytes, so the Guardrail Engine — and the human,
  if policy says to ask — decides before the sender transmits anything.
- Automatic expiry via `FileDrop.isExpired()`, with an explicit carve-out:
  a transfer mid-flight is never treated as expired, however old its
  timestamp gets.
- A declined offer never touches disk. An outbound file's bytes stay in
  memory only until sent, then are discarded — only the receiver keeps a
  persisted copy.
- 12 new `FileTransferManager` unit tests plus an end-to-end integration
  test exercising the full flow (multi-chunk transfer, ask-user, decline,
  default-deny, unconditional-allow for sending) over two independent
  `MeshManager`s on a loopback transport.
- Android: pick a file to send and save a received one via the standard
  Storage Access Framework contracts (`GetContent`, `CreateDocument`) —
  no new dependency, but not yet exercised on a device.

## Encryption at rest

- Closes the gap named in ADR 0002: every persisted file — audit log,
  trust decisions, shared lists, capabilities, file drops — is now sealed
  with AES-256-GCM before it touches disk.
- `EncryptedFileStore` wraps the existing `FileStore` interface
  transparently: `FileAuditLog`, `FileTrustStore`, `SharedListRepository`,
  `CapabilityRegistry`, and `FileDropStore` needed zero code changes.
  Turned on for the whole app in one line at the composition root.
- Per-record sealing so `writeText` and `appendLine` compose correctly —
  `FileAuditLog`'s compact-then-continue pattern mixes both on the same
  file. A hand-traced bug was caught and fixed before it shipped: the
  first version of `writeText` didn't terminate its record with a newline,
  so a following `appendLine` would have concatenated onto it with no
  separator.
- `AtRestCipher` is opaque (seal/open, no raw key bytes) for the same
  reason `EcdhKeyPair` hides its private key behind a handle — the real
  implementation (`AndroidKeystoreAtRestCipher`) is a non-extractable
  AES-256-GCM key generated inside the Android Keystore and never
  exported, mirroring the identity-key decision in ADR 0001.
- File name is bound as AEAD associated data, so a ciphertext sealed for
  one file cannot be passed off as another's.
- 14 new tests covering round trips, the compact-then-continue pattern,
  tamper tolerance (a corrupt record is skipped, not fatal), wrong-key
  rejection, AAD binding, and two integration tests proving
  `FileTrustStore` and `FileAuditLog` work unchanged when wrapped. The
  real Keystore cipher itself, like the Keystore identity key, cannot be
  unit-tested outside a device — full rationale in
  `docs/adr/0005-encryption-at-rest.md`.

## Status board

- Group status / coordination board: a short, self-expiring status
  ("at the north gate") broadcast to connected peers over the existing
  encrypted mesh pipeline. New `Envelope.StatusPost`, `ActionType.SHARE_STATUS`
  (default-allow, same posture as messaging), and an off-by-default
  "only share status with people I've verified" policy mirroring the
  equivalent list-sync option.
- `StatusBoardManager` holds the board in memory only — deliberately not
  persisted, since a stale status is misleading rather than merely wasted
  space, unlike lists, capabilities, or file drops.
- Each status carries its own expiry rather than trusting the recipient's
  clock, so both sides agree on when it goes stale; `currentStatus()` filters
  point-in-time, and a periodic `purgeExpired()` keeps the reactive board
  state fresh without waiting for a read.
- 10 new `StatusBoardManager` unit tests (expiry boundary, truncation,
  per-peer independence, purge) plus an end-to-end integration test proving
  a status survives the real handshake/encryption/guardrail pipeline,
  including the verified-only policy path.
- Android: a status composer on the Nearby screen's identity card, and each
  connected peer's current status shown inline on their row; wired through
  the ViewModel with a 60-second purge ticker.

## Phase 4 — Hardening & Polish (in progress)

**Per-peer rate limiting**

- Closes part of the gap named in docs/THREAT_MODEL.md #6: nothing
  previously stopped a peer from flooding connection attempts or messages,
  costing this device unbounded CPU, battery, and — worse — unbounded
  AskUser prompts, before any policy rule ever got a chance to say no.
- New `RateLimiter`: a fixed-window counter per peer, checked in
  `DefaultGuardrailEngine` before the safety floor, since it's equally
  non-negotiable. Default: 50 inbound requests per 10 seconds per peer.
  Only inbound requests are throttled — an outbound action is this
  device's own choice, with no one else to protect it from.
- Covers connection-attempt flooding as well as post-handshake message,
  list, file, and status floods, since all of them funnel through the same
  `GuardrailEngine.evaluate()` choke point.
- 9 new tests: the limiter's own window/reset/per-key-independence
  behaviour, plus engine-level tests proving a flood is throttled, the
  budget resets after the window elapses, the limit is per-peer rather
  than global, and outbound actions are never throttled.

**Nearby-peer list leak**

- `mergeDiscovered` only ever added or updated entries from a scan cycle,
  never removed one — every device ever seen nearby stayed listed as "in
  range" forever, growing without bound over a long session (a real memory
  and UI-correctness bug, and part of the same battery/resource concern as
  the rate limiter above).
- Fixed: a peer absent from the current scan with no active link is now
  dropped. A peer that's connecting, handshaking, or already secured is
  kept regardless of what the scan reports, since BLE doesn't reliably
  re-advertise a device it already holds a GATT connection to — a single
  missed scan cycle must never make a connected peer vanish from the screen.
- 2 new integration tests: a peer walking out of range disappears from the
  list; a secured peer survives a scan cycle that fails to re-report it.

**Internal connection-state leaks**

- `links` (per-address session/handshake/frame-buffer bookkeeping) was
  only ever cleared by the public `disconnect()` method. A failed
  handshake, a denied inbound connection, a user-declined connection, or a
  failed outbound send all left their entry behind forever — every attempt
  anyone ever made, accepted or not, sat in memory for the life of the
  process. New `forgetLink()` centralizes removal and is now called from
  all four of those paths.
- With that fixed, it became safe to close the matching gap in
  docs/THREAT_MODEL.md #3 (Sybil / identity flooding): `onIncoming`
  created an entry for *any* address sending *any* frame, before that
  frame was validated or attributed to an identity, which meant a flood
  from constantly-rotating addresses grew memory without bound and
  bypassed the per-peer rate limit entirely (a new address always gets a
  fresh limiter budget). `MAX_CONCURRENT_LINKS` (40) now refuses a
  never-before-seen address once reached, without evicting anything
  already tracked. Doing this before the leak fix above would have meant
  the cap itself eventually locking out real peers after enough distinct
  encounters in a long session.
- New integration test floods 41 distinct addresses and confirms the 41st
  never reaches the Guardrail Engine at all, while the other 40 do.
