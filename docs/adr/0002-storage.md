# ADR 0002 — Local storage format

Status: accepted
Date: 2026-08-28
Supersedes the SQLDelight choice in the original stack decision.

## Context

Kodama must persist three things across restarts:

1. The **audit log** — the record of every Guardrail decision.
2. The **trust store** — which peers the user has verified in person.
3. Conversation history.

The original stack named SQLDelight (with encryption). This ADR records
why the first implementation does not use it.

## Decision

Store the audit log as **JSON Lines** (one entry per line, appended) and
the trust store as a small **JSON document**, behind a `FileStore`
interface with an Android implementation over app-private storage.

### Why not SQLDelight, for now

- **The audit log is append-only by nature.** Appending a line is a natural
  fit; a table with an autoincrement key and a periodic `DELETE` is more
  machinery for the same result.
- **"Readable" is a stated product promise.** The project tells users their
  activity log is theirs to inspect. A text file honours that literally.
  A SQLite database requires a tool.
- **Verifiability.** SQLDelight generates code at build time via a Gradle
  plugin. The storage logic that actually matters — bounding, compaction,
  recovery from a torn write, failing closed on corruption — is ordinary
  Kotlin and is unit-tested in `PersistenceTest`.
- **Volume doesn't justify it.** A bounded log of ~1000 entries and a set
  of verified device IDs are small. Indexed queries buy nothing here.

The unused SQLDelight plugin and dependencies have been removed rather than
left configured, so the build does not carry machinery nothing uses.

## Durability

Writes that replace a whole file (the trust store, and log compaction) go
to a temporary file and are renamed over the target. A crash mid-write
leaves either the old file or the new one — never a truncated file that
would parse as valid but wrong.

The audit log tolerates a torn final line: an unparseable line is skipped
and counted, rather than failing the whole load. Losing one entry is much
better than losing the history.

The trust store **fails closed**. If its file cannot be parsed, the store
starts empty, so the user is asked to verify again. The alternative —
trusting an unreadable list — would be a security failure.

## What this does not yet do

- **Conversation history is not persisted yet** — only the audit log and
  trust store are. Messages are still lost on restart.

Encryption at rest — originally listed here as a gap — is resolved: see
`docs/adr/0005-encryption-at-rest.md`. It layers onto `FileStore`
transparently rather than requiring a database, which is what let it be
added without touching `FileAuditLog`, `FileTrustStore`, or anything else
built on this interface.

## When to revisit

Reintroduce SQLDelight (or SQLCipher) if any of these become true:

- Conversation history grows enough to need indexed queries or paging.
- Multi-table relational queries appear (e.g. capability grants joined
  against peers).

The `FileStore`, `AuditLog`, and `TrustStore` interfaces exist so that
change stays behind them.
