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
│  safety floor → user rules → category default│
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
| `guardrail/` | Engine, rules, `PolicyCatalog`, audit log |
| `mesh/` | `MeshTransport` interface, `MeshManager` |
| `domain/` | `Peer`, `ChatMessage`, `Conversation` |

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
2. `Frame.decode` — returns null for anything malformed. No exceptions
   escape into the transport.
3. `MessageAssembler.offer` — reassembles chunks under a memory bound.
4. Handshake frames drive the handshake; sealed frames go to
   `SecureSession.open`, which rejects replays and forgeries silently.
5. `EnvelopeCodec.decode` — null on malformed input.
6. The Guardrail Engine evaluates a `GuardrailRequest` for the action.
7. Only then does the payload reach application state.

Each of steps 2, 3, 5 and 6 can drop the message. That's the intended
shape: a hostile peer's best case is being ignored.

## Threading

- `MeshManager` runs on a `SupervisorJob` scope owned by the composition
  root, on `Dispatchers.Default`.
- Its internal maps are guarded by a `Mutex`. The mutex is never held
  across a transport call, so a slow radio cannot deadlock the manager.
- `SecureSession` and `MessageAssembler` are documented as not thread-safe
  and are confined to `MeshManager`'s coroutines.

## Known architectural gaps

- **Persistence.** `InMemoryAuditLog` and `InMemoryTrustStore` are
  placeholders. The interfaces are shaped for SQLDelight but the durable
  implementations do not exist, so verification decisions and the audit log
  do not survive a restart. This is the most significant gap.
- **Single hop.** `MeshManager` has no routing table; `RELAY_MESSAGE`
  exists as a policy concept but nothing forwards yet.
- **Lifecycle.** No foreground service, so the mesh stops when the app is
  backgrounded.

## Status

The layering and interfaces described here are implemented. The gaps above
are named as gaps rather than described as if they exist.
