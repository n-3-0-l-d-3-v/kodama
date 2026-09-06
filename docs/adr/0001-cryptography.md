# ADR 0001 — Cryptographic primitives and the session handshake

Status: accepted
Date: 2026-08-27

## Context

Kodama exchanges data between phones that have never met, over a
radio anyone in range can listen to, with no server and no certificate
authority. We need device identity, an encrypted channel, and a handshake
that binds one to the other — using primitives that actually exist on
Android API 26+.

## Decisions

### Identity: P-256 ECDSA in the Android Keystore

Rejected: Ed25519 via Tink (the original Phase 1 implementation).

- Keystore keys are **non-extractable**. On a device with a TEE or
  StrongBox the private key never enters app memory, so malware with full
  app-storage access still cannot steal the device identity. Tink's
  `AndroidKeysetManager` stores a software keyset *wrapped* by a Keystore
  key — better than plaintext, but the raw private key is still
  reconstructed in process memory on every use.
- Keystore has supported EC P-256 since API 23; Ed25519 support is recent
  and uneven across OEMs.
- It removes a third-party dependency from the most security-critical path.

Cost: ECDSA needs a secure random nonce per signature and is less
misuse-resistant than Ed25519 in principle. The Keystore implementation
handles nonce generation internally, so this risk is not ours to carry.

### Key agreement: P-256 ECDH (ephemeral)

Rejected: X25519.

X25519 only reached Android's platform providers in API 33. Our floor is
API 26. Bundling a software implementation would mean shipping and
maintaining our own curve code — a worse trade than using the
well-tested P-256 that is already hardware-accelerated on these devices.

Ephemeral keys are generated per session and are *not* Keystore-backed:
they live in memory only and are discarded with the session, which is what
gives the channel forward secrecy.

### Key derivation: HKDF-SHA256, implemented in shared code

The raw ECDH output is never used as a key. It is run through HKDF
(RFC 5869), with:

- **salt** = both handshake nonces, ordered deterministically
- **info** = a protocol label plus direction

HKDF is implemented in `commonMain` rather than delegated to each
platform, specifically so it can be checked against the RFC's published
test vectors. A subtly wrong key schedule still emits plausible random
bytes; only vectors catch that.

Two **directional** keys are derived from one shared secret — one for each
direction of travel. With a single shared key, an attacker could replay a
device's own frames back at it and they would authenticate.

### Record encryption: AES-256-GCM

Standard, hardware-accelerated, and available everywhere we target. The
frame header is passed as **associated data**, so a valid ciphertext
cannot be replayed under a different header.

Nonces are 96-bit: a 32-bit random per-session prefix plus a 64-bit
monotonic counter. GCM catastrophically fails on nonce reuse, so the
counter is per-direction and a session refuses to encrypt past counter
exhaustion rather than wrapping.

### Handshake

```
A → B   Hello    { identityPub, ephemeralPub, nonceA, sig, displayName }
B → A   HelloAck { identityPub, ephemeralPub, nonceB, sig, displayName }
```

The signature covers a transcript binding the ephemeral key to the
identity key and both nonces. Without it, anyone in the middle could swap
in their own ephemeral key — the classic unauthenticated-DH break.

## What this does *not* give us

Being explicit, because the gap matters:

**A signature proves key continuity, not who is holding the phone.** On
first contact, a peer's identity key is unknown to us; the handshake
proves the far end holds that key's private half, and nothing more. It
does not establish that the human is who they claim.

A machine-in-the-middle on *first* contact is therefore not prevented by
this handshake alone. It is closed by out-of-band verification — the
`fingerprint` code compared in person or by QR (Phase 3, "I know this
person"). Until a peer is verified, the UI must present it as unverified,
and the Guardrail Engine treats it accordingly. Subsequent sessions are
protected against substitution because the identity key is pinned.

## Consequences

- No third-party crypto dependency in the app.
- Key derivation is unit-tested against published vectors.
- We inherit P-256's larger keys and slower operations versus modern
  curves. At BLE data rates this is irrelevant.
- If our API floor ever rises to 33, X25519 + Ed25519 becomes the
  preferred migration, and the protocol version field exists to negotiate
  it.
