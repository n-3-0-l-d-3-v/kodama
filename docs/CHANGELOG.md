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
