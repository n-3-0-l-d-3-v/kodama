# ADR 0005 — Encryption at rest

Status: accepted
Date: 2026-09-11

## Context

ADR 0002 named this a known gap: everything persisted through `FileStore`
— the audit log, trust decisions, shared lists, capabilities, file drops —
was stored as plain JSON or hex, relying entirely on Android's app-private
storage and full-disk encryption. That protects against another app on the
device, but not against anything with filesystem access to an unlocked or
adb-backed-up device — an adb backup, a rooted forensic tool, or a device
image taken while unlocked.

## Decision

### A transparent `FileStore` decorator, not a new storage engine

`EncryptedFileStore` wraps any `FileStore` and implements the same
interface. Every class that persists something — `FileAuditLog`,
`FileTrustStore`, `SharedListRepository`, `CapabilityRegistry`,
`FileDropStore` — needed zero changes. The composition root
(`ProximityApplication.kt`) wraps `AndroidFileStore` once; encryption is on
everywhere, or nowhere, from that single line.

The alternative — encrypting each store's own serialization format
individually — would mean five call sites to get right instead of one, and
five chances to forget. A decorator over the shared interface those
classes already depend on was the only design considered.

### Per-record sealing, so `writeText` and `appendLine` compose

`FileAuditLog` mixes two write patterns on the same file: `appendLine` per
entry, and an occasional `writeText` during compaction that replaces the
whole file with several joined lines. `EncryptedFileStore` seals whichever
string it's given — one call, one record — and stores that record as a
single hex line. Reading splits the raw file back into records, opens each
independently, and rejoins the plaintexts with `\n`. A `writeText` of a
multi-line string is simply one record whose *opened plaintext* contains
the embedded newlines; it interleaves correctly with surrounding
single-line `appendLine` records either side of it.

**A real bug this caught before it shipped:** the first version of
`writeText` didn't terminate its record with a trailing newline. A
following `appendLine` would then concatenate its own record directly onto
the end with no separator, producing one unparseable line. Hand-tracing
`FileAuditLog`'s actual compact-then-continue call sequence against the
test I was about to write caught it — `EncryptedFileStoreTest.writeThenAppendMatchesFileAuditLogsCompactThenContinuePattern`
pins the fixed behaviour.

A record that fails to open — tampered, truncated, or sealed under a key
that no longer exists — is skipped rather than failing the whole read.
This preserves `FileAuditLog`'s own "lose one entry, not the whole
history" tolerance for corruption, and a caller that instead expects one
all-or-nothing document (the trust store) already treats an empty or
unparseable result as "start fresh" in its own loader — see ADR 0002.

### The cipher is opaque, not a raw-bytes key

`AtRestCipher` exposes `seal(aad, plaintext)` / `open(aad, sealed)` — no
key material ever crosses this interface. This mirrors
`EcdhKeyPair.privateHandle: Any` from ADR 0001, and for the identical
reason: `AndroidKeystoreAtRestCipher`'s AES-256-GCM key is generated inside
the Keystore and never leaves it, at any point, on a device with a TEE or
StrongBox. A raw-bytes key parameter would have forced the key to exist in
extractable form just to satisfy the interface, defeating the point — the
same trade ADR 0001 rejected when it moved device identity off Tink's
software-keyset-wrapped-by-Keystore approach onto a Keystore-native key.

Every AAD binds the file name being read or written, so a ciphertext
sealed for one file cannot be passed off as another's — a defence against
a bug (or bytes maliciously relocated between files) rather than against a
realistic external attacker, since nothing else on the device can reach
this app's private storage in the first place.

### Testability without Android Keystore

`AndroidKeystoreAtRestCipher` cannot be unit-tested outside a real device
or emulator — Keystore is unavailable to a plain JVM, exactly like
`KeystoreDeviceIdentityProvider` in ADR 0001. `EncryptedFileStoreTest`
covers the actual complexity — the per-record format, corruption handling,
AAD binding, and the two real callers (`FileTrustStore`, `FileAuditLog`)
composed through it — using a plain-JVM AES-GCM stand-in cipher, the same
pattern `HandshakeTest` already established for standing in for Keystore
identity keys.

## Consequences

- Every current `FileStore` consumer is encrypted with no code changes,
  because all of them already depended on the interface rather than the
  concrete `AndroidFileStore`.
- `AppSettings` (display name, onboarding state, which policies are
  enabled) stays on plain `SharedPreferences`, deliberately out of scope —
  none of it carries the sensitivity of the mesh's own record of
  who-did-what.
- Storage overhead roughly doubles per record (nonce/IV, GCM tag, hex
  encoding) on top of `FileDropStore`'s existing hex encoding of file
  bytes. Acceptable at the data volumes involved (a bounded audit log, a
  small trust set, transfers already capped at 200 KB).
- Not yet run on a device — `AndroidKeystoreAtRestCipher` is the one piece
  of this feature that couldn't be verified by the JVM test harness this
  project relies on.
