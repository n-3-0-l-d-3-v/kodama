# Architecture

## Overview

Kodama is layered so the riskiest code (radios, peer-supplied bytes)
sits farthest from the UI, and nothing crosses between them without passing
through the Guardrail Engine.

```
┌─────────────────────────────────────────────┐
│          Compose Multiplatform UI            │
│   Nearby · Chat · Activity · Rules           │
└─────────────────────┬───────────────────────┘
                      │  ProximityViewModel
┌─────────────────────▼───────────────────────┐
│                MeshManager                   │
│   the only path between radio and app        │
└─────────────────────┬───────────────────────┘
                      │
┌─────────────────────▼───────────────────────┐
│              Guardrail Engine                │
│ rate limit → safety floor → rules → default  │
│  every decision appended to the audit log    │
└─────────────────────┬───────────────────────┘
                      │
┌─────────────────────▼───────────────────────┐
│        Session · Identity · Crypto           │
│  handshake, SecureSession, Keystore identity │
└─────────────────────┬───────────────────────┘
                      │
┌─────────────────────▼───────────────────────┐
│              Protocol (framing)              │
│  chunking · reassembly · envelopes           │
└─────────────────────┬───────────────────────┘
                      │
┌─────────────────────▼───────────────────────┐
│           Transport (BluetoothLE)            │
│  scan · advertise · GATT client + server     │
└─────────────────────────────────────────────┘
```

## The central invariant

**Every inbound frame and every outbound action passes through
`MeshManager`, and `MeshManager` consults the Guardrail Engine before
acting.**

This is enforced structurally rather than by convention:

- `MeshTransport` is an interface with no public surface that returns
  application data. It emits raw bytes and accepts raw bytes.
- Only `MeshManager` holds a `MeshTransport`.
- The UI holds a `ProximityViewModel`, which holds a `MeshManager`. It has
  no route to the transport at all.

If a future change needs a new capability, the way to add it is a new
`ActionType` — which forces a policy decision to exist for it, because the
engine's fallback is deny.

## Module boundaries

**`shared`** (Kotlin Multiplatform) — all platform-agnostic logic:

| Package | Responsibility |
|---|---|
| `crypto/` | `CryptoPrimitives` interface, HKDF, `SecureSession` |
| `session/` | Handshake state machine, length-prefixed transcripts |
| `identity/` | `DeviceIdentity`, `SignatureVerifier`, `TrustStore` |
| `protocol/` | `Frame`, chunking, reassembly, `Envelope` + codec |
| `guardrail/` | Engine, rate limiter, rules, `PolicyCatalog`, audit log |
| `mesh/` | `MeshTransport` interface, `MeshManager`, per-feature delegate interfaces |
| `domain/` | `Peer`, `ChatMessage`, `Conversation`, `ConversationStore` |
| `lists/` | Shared list CRDT (`SharedListEngine`) and repository |
| `capability/` | What a device offers, and for how long (`CapabilityRegistry`) |
| `files/` | File transfer state machine and persistence |
| `status/` | Status board domain model and `StatusBoardManager` |
| `storage/` | `FileStore` interface, at-rest encryption (`EncryptedFileStore`) |

**`androidApp`** — Android specifics only: the BLE transport, JCA/Keystore
implementations of the shared crypto and identity interfaces, and the
Compose UI.

## Why the platform surface is small

`CryptoPrimitives` exposes only RNG, SHA-256, HMAC, ECDH, and AES-GCM.
Everything built from those — key derivation, transcripts, the session key
schedule, nonce management — lives in `shared`.

This is a deliberate trade. Per-platform crypto composition would mean
writing the key schedule twice and verifying it nowhere. Keeping it shared
means HKDF can be checked against RFC 5869 vectors once and both platforms
inherit that guarantee.

## Data flow: receiving a message

1. BLE GATT server receives a write → `IncomingMessage(address, bytes)`.
2. `MeshManager` tracks connection state per transport address; a
   never-before-seen address is refused outright once
   `MAX_CONCURRENT_LINKS` distinct addresses are already tracked.
3. `Frame.decode` — returns null for anything malformed. No exceptions
   escape into the transport.
4. `MessageAssembler.offer` — reassembles chunks under a memory bound.
5. Handshake frames drive the handshake; sealed frames go to
   `SecureSession.open`, which rejects replays and forgeries silently.
6. `EnvelopeCodec.decode` — null on malformed input.
7. The Guardrail Engine evaluates a `GuardrailRequest` for the action —
   first against the per-peer rate limit, then the safety floor, user
   rules, and category default, in that order.
8. Only then does the payload reach application state.

Each of steps 2–4, 6, and 7 can drop the message. That's the intended
shape: a hostile peer's best case is being ignored.

## Threading

- `MeshManager` runs on a `SupervisorJob` scope owned by the composition
  root, on `Dispatchers.Default`.
- Its internal maps are guarded by a `Mutex`. The mutex is never held
  across a transport call, so a slow radio cannot deadlock the manager.
- `SecureSession` and `MessageAssembler` are documented as not thread-safe
  and are confined to `MeshManager`'s coroutines.

## Known architectural gaps

- **Single hop.** `MeshManager` has no routing table; `RELAY_MESSAGE` and
  the "help carry other people's messages" policy exist as a concept, but
  nothing forwards traffic between two peers that can't reach each other
  directly. The biggest remaining gap, and one that needs its own crypto
  design (mailbox/static keys for store-and-forward) before attempting.
- **iOS and Wi-Fi Direct/Aware.** Both need toolchains this project's
  build environment cannot compile or test; see README "Not built yet".
- **Revocation.** No way to distribute "this device is known compromised"
  to other peers — see docs/THREAT_MODEL.md #8.

`InMemoryAuditLog` and `InMemoryTrustStore` still exist (used by every
test in this codebase, since a test has no reason to touch disk), but are
no longer the only implementations: `FileAuditLog` and `FileTrustStore`
back the real app, encrypted at rest (docs/adr/0005-encryption-at-rest.md).
A foreground service also now exists, opt-in, so the mesh can keep running
while the app is backgrounded (docs/CHANGELOG.md, "Background operation").

## Status

The layering and interfaces described here are implemented, well past the
Phase 0 scaffold this document originally described. The gaps above are
named as gaps rather than described as if they exist; see
docs/CHANGELOG.md for what has shipped since and README "Current state"
for the up-to-date feature list.
