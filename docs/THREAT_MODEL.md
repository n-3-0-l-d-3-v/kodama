# Threat Model

Living document. Update whenever a feature introduces a new attack surface,
or a new attack is identified against an existing one.

Each threat below is marked with its current status:

- **Mitigated** — a specific mechanism exists and is tested.
- **Partial** — some defence exists, with a named gap.
- **Open** — identified, not yet addressed.

## Assets

- Message and file contents exchanged over the mesh.
- Device identity keys (long-term) and session keys (ephemeral).
- Capability tokens — what a peer is currently permitted to do.
- The local audit log, as evidence of what actually happened.
- Location and presence: who is near whom, and when.
- Battery and availability of the device as a mesh participant.

## Trust boundaries

- Any device in radio range is untrusted until proven otherwise.
- A peer that completes a handshake has proven **key ownership**, not
  **identity**. It stays `UNVERIFIED` until a human compares fingerprints
  out of band.
- A verified peer is trusted only for explicitly granted capabilities.
- Relays must never require plaintext access to what they carry.

## Threats

### 1. Malicious nearby peer — *Mitigated*

A device in range sending malformed, oversized, or hostile frames.

- `Frame.decode` returns null rather than throwing, and is fuzzed against
  random input in tests.
- `MessageAssembler` bounds total message size, caps simultaneous in-flight
  messages, evicts stale partials, ignores duplicate chunks, and drops a
  message whose declared shape changes mid-stream.
- `EnvelopeCodec.decode` returns null on malformed input.
- Default-deny means an action with no matching rule is refused.

### 2. Capability and identity spoofing — *Mitigated*

A device claiming an identity or capability it does not hold.

- A device ID is derived from its public key, so claiming another device's
  ID requires that device's private key.
- The handshake signature covers a length-prefixed transcript binding the
  ephemeral key to the identity key. Substituting either fails verification.
- Length-prefixed transcripts prevent boundary-shifting attacks where a
  signature over one message validates another.
- On an inbound connection, the peer's *claimed* identity is deliberately
  treated as `UNVERIFIED` for the policy decision, because its signature has
  not been checked yet. Otherwise anyone could skip the connection prompt by
  asserting a verified peer's public key.

### 3. Sybil / identity flooding — *Partial*

One attacker creating many identities to appear as a crowd.

Identities are free to create by design (no account, no authority), so
scarcity cannot be the defence. Instead, trust comes from human
verification, and the UI never presents an unverified peer as trustworthy.

**Gap:** nothing yet rate-limits how many distinct identities may be
entertained from one radio neighbourhood, and routing does not yet weight
verified peers over unverified ones.

### 4. Bluetooth fingerprinting and tracking — *Partial*

A passive observer correlating advertisements to track someone's movement.

- The BLE advertisement carries the service UUID only.
  `setIncludeDeviceName(false)` is set explicitly — the device name is
  frequently the owner's real name.
- The display name is chosen during onboarding and defaults to a generic
  value rather than the phone's Bluetooth name.
- The display name is transmitted only *after* a session is encrypted, not
  in the clear.

**Gap:** we rely on the platform's MAC randomisation and do not rotate any
identifier of our own. A stationary observer can still correlate a session
across its lifetime.

### 5. Data exfiltration — *Partial*

Moving local data off the device without the user's knowledge.

- Contacts, calendar, and location have no default-allow path; all fall to
  default-deny.
- Location sharing requires a policy to be enabled *and* per-use
  confirmation.
- `LEAVE_MESH` exists as a distinct action so escaping to the internet is a
  policy decision, not an implementation detail.
- The audit log records attempts, so exfiltration is visible after the fact
  even if a decision was wrong.

**Gap:** the app does not yet hold contacts/calendar permissions at all, so
these paths are untested. `LEAVE_MESH` has no enforcement point because
there is no internet code to gate.

### 6. Battery exhaustion — *Partial*

Forcing radio activity or work to drain a target.

- The audit log is bounded, so a peer spamming requests cannot grow memory
  without limit.
- In-flight message count and reassembly memory are capped per peer.
- Connection and write operations have timeouts, so a peer cannot pin
  resources by stalling.

**Gap:** no per-peer rate limiting or connection-attempt backoff, and no
battery-aware degradation (e.g. ceasing to relay below a threshold).

### 7. Regaining internet connectivity — *Open*

A mesh-only device reconnecting to the internet while holding mesh data.

The default-deny posture covers this in principle, and `LEAVE_MESH` is
modelled. But there is no networking code, so there is nothing to enforce
and nothing to test. Revisit before any online feature ships.

### 8. Single device compromise — *Partial*

One participant's phone is compromised and used against the mesh.

- Identity keys are generated in the Android Keystore and are
  non-extractable. On a device with a TEE or StrongBox they never enter
  process memory, so malware with full app-storage access still cannot
  steal the identity.
- Session keys are ephemeral, so traffic captured earlier is not
  retroactively readable from a later compromise.
- A compromised peer can act only within the capabilities others granted
  it; each device enforces policy locally.

**Gap:** no revocation. If a device is known compromised, peers have no way
to distribute that fact.

### 9. Machine-in-the-middle on first contact — *Open, by design*

This is the most important honest limitation.

The handshake proves the far end holds the private key matching the public
key it presented. On a **first** contact, we have no prior knowledge of
that key, so an attacker who relays between two parties — presenting their
own key to each — completes a valid handshake with both.

This is not solvable by cryptography alone without a trust anchor, and
Kodama deliberately has no central authority. It is closed by
**out-of-band verification**: the short fingerprint compared in person.

Accordingly:

- Peers are `UNVERIFIED` until a human says otherwise.
- The chat screen shows an explicit notice explaining that encryption does
  not prove who holds the other phone.
- Once verified, substitution is prevented, because the device ID is
  derived from the pinned key.

**Update:** verification no longer depends solely on reading a fingerprint
aloud. Either side can show a QR code (`QrVerificationCodec`, encoding their
identity public key) for the other to scan. Scanning from an open
conversation cross-checks the scanned key against that conversation's
device ID and refuses to verify on a mismatch, rather than trusting
whatever was scanned; scanning from the Nearby screen with no conversation
open pre-authorises whoever the key belongs to, since the in-person scan
*is* the verification. Manual fingerprint comparison remains available as
a fallback for a phone with no camera or a broken scanner.

**Remaining gap:** the codec has full test coverage, but the scan flow
itself — camera permission, the third-party scanner activity, and the
UI wiring — has not been exercised on a device.

## Explicitly out of scope

- A compromised OS or kernel on the user's own device.
- Physical-layer attacks (jamming, RF hardware fingerprinting).
- Formal verification of the protocol (stretch goal).
- Traffic analysis: an observer can tell that two devices are communicating
  and roughly how much, even without reading it.
