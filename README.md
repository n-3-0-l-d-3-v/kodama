# Kodama

**木霊 (kodama)** — in Japanese folklore, a spirit said to live in old trees
and answer back when you call into the forest; the same word is also the
ordinary Japanese noun for *echo*. Both meanings fit: this app forms only
where devices are close enough to hear each other, answers back, and
disappears the moment they're not.

Kodama is a temporary, local coordination layer that forms
automatically between nearby smartphones. Devices discover each other over
Bluetooth LE, prove who they are, open an encrypted channel, and exchange
data — with no internet, no account, and no server anywhere.

Every action that touches the mesh, in or out, passes through the
**Guardrail Engine**: a first-class, user-controllable policy system.
Nothing sensitive happens without an explicit, explainable decision, and
every decision is written to a local audit log the user can read.

> **Status: early development.** Phase 1 (core connectivity) is
> substantially built and unit-tested, but has not yet been run between two
> physical phones. See [Current state](#current-state) for an honest
> breakdown of what works and what doesn't.

## Why

Existing mesh and offline messengers optimise for reachability and treat
safety as an afterthought. Kodama starts from the opposite
assumption: **default deny**, every block explained in plain language, and
every decision recorded where the user can check it.

The test we hold features to is: *would a non-technical person understand
what this app just did, and why?*

## Core principles

1. **Default deny** — anything not explicitly allowed is blocked.
2. **User understanding** — every blocked or sensitive action is explainable in plain language.
3. **No central account required** for basic use.
4. **Battery and privacy first.**
5. **Capability-based** — devices advertise short-lived capabilities, not open access.
6. **Auditability** — every significant decision is logged locally, readably.
7. **Continuous shipping** — small, frequent, verifiable commits.

## Current state

**Working and unit-tested**

- Wire protocol: framing, chunking to the negotiated BLE MTU, reassembly
  with bounded memory and hostile-input handling.
- Cryptography: P-256 ECDH, HKDF-SHA256 (checked against RFC 5869
  vectors), AES-256-GCM with per-direction keys, monotonic nonces, and
  replay rejection.
- Device identity: P-256 ECDSA keypair generated in the Android Keystore;
  the private key is non-extractable.
- Authenticated handshake binding ephemeral keys to long-term identities.
- Guardrail Engine: safety floor, user-configurable rules, category
  defaults, and an audit log entry for every decision.
- `MeshManager`: the single choke point between the radio and the app.
- Durable storage for the audit log, verified-peer decisions, and shared
  lists, with atomic writes, corruption recovery, and fail-closed trust
  loading.
- Shared lists that merge correctly after devices edit them while apart,
  with convergence verified by property tests.
- Short-lived capability advertisement, filtered and expiry-capped on
  receipt.
- QR-code verification: scan to confirm a conversation's peer, or
  pre-authorise a stranger before ever connecting. The codec is fully
  tested; the on-device scan flow (camera, third-party scanner activity) is
  not.
- File transfer with automatic expiry, up to 200 KB per file (see
  docs/adr/0004-file-transfer.md). Offer/accept/decline/data all tested
  end to end over the real mesh stack, including the ask-the-user flow;
  the Android file-picker/save-file UI (Storage Access Framework) is not
  yet exercised on a device.
- Conversation history that survives restarts, with bounded retention.
- An opt-in foreground service so the mesh can keep running when the app is
  not in front.
- Bluetooth LE transport: symmetric scan/advertise, GATT client and server,
  serialised writes, MTU negotiation.
- UI: onboarding, nearby devices, chat, audit log, and policy settings.

**Not built yet**

- Multi-hop relay and store-and-forward (messages only travel one hop).
- Encryption at rest for stored data (see docs/adr/0002-storage.md).
- Wi-Fi Direct/Aware transport.
- iOS.

**Not yet verified**

The two-device flow has been exercised end to end in an in-memory
integration test, but never over real radios between two physical phones.
Treat BLE behaviour as unproven until it has been.

## Security summary

- Identity keys live in the Android Keystore and cannot be extracted.
- Sessions use ephemeral keys, so past traffic stays protected if a device
  is later compromised.
- The handshake proves the far end holds the private key it claims — but
  **not** who is holding the phone. That gap is closed by comparing the
  short fingerprint in person. Until then the UI marks a peer *unverified*,
  and says why.
- The advertised Bluetooth payload contains no stable, user-identifying
  name, to limit passive tracking.

Details and rationale: [docs/adr/0001-cryptography.md](docs/adr/0001-cryptography.md)
and [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

## Tech stack

- **Kotlin Multiplatform** for all shared logic: Guardrail Engine, protocol,
  crypto, identity, mesh orchestration, domain models.
- **Compose Multiplatform** (Material 3) for UI, Android first.
- **Android** (primary): Bluetooth LE, Android Keystore, JCA.
- **iOS** (later): Core Bluetooth, same shared core.

No third-party cryptography library — the primitives come from the platform
and the composition (HKDF, transcripts, session handling) is our own shared,
tested code. The one external dependency in the Android app is ZXing, for
generating and scanning QR verification codes — a rendering detail, not a
security primitive; the codec it renders is our own and is fully tested.

## Project layout

```
kodama/
├── shared/          # KMP core
│   └── src/commonMain/kotlin/os/proximity/shared/
│       ├── capability/ # what a device offers, and for how long
│       ├── crypto/     # primitives interface, HKDF, SecureSession
│       ├── domain/     # Peer, ChatMessage, Conversation
│       ├── files/      # file transfer state machine and persistence
│       ├── guardrail/  # engine, rules, policy catalog, audit log
│       ├── identity/   # device identity, trust store
│       ├── lists/      # shared list CRDT and repository
│       ├── mesh/       # MeshTransport interface, MeshManager
│       ├── protocol/   # framing, chunking, envelopes
│       └── session/    # handshake, transcripts
├── androidApp/      # Android app
│   └── src/main/kotlin/os/proximity/android/
│       ├── mesh/       # BLE transport
│       ├── verification/ # QR code generation
│       ├── files/      # ContentResolver read/write helpers
│       └── ui/         # screens, components, theme
└── docs/            # architecture, threat model, policy design, ADRs
```

## Building

Requires JDK 17+ and the Android SDK (Android Studio provides both).

```bash
./gradlew :androidApp:assembleDebug
```

Run the test suites — the shared logic is where the guarantees live:

```bash
./gradlew :shared:testDebugUnitTest
```

If Gradle cannot run in your environment (it needs a loopback socket for
its daemon, which some sandboxes block), the same suites can be compiled
and run directly:

```bash
powershell -ExecutionPolicy Bypass -File tools/verify-shared.ps1
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Guardrail policy design](docs/GUARDRAIL_POLICY.md)
- [ADR 0001 — Cryptography](docs/adr/0001-cryptography.md)
- [ADR 0002 — Local storage](docs/adr/0002-storage.md)
- [ADR 0003 — Shared lists](docs/adr/0003-shared-lists.md)
- [ADR 0004 — File transfer](docs/adr/0004-file-transfer.md)
- [Changelog](docs/CHANGELOG.md)

## License

TBD.
