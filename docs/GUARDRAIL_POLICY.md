# Guardrail Policy Design

The Guardrail Engine is the single mediation point for every sensitive
action in Kodama, inbound or outbound. This document describes the
policy model it evaluates.

## Goals

- **Default deny**: an action is blocked unless a rule explicitly allows it.
- **Explainable**: every decision (allow, deny, ask) has a plain-language
  reason a non-technical person can read.
- **Composable**: rules combine predictably; there is one evaluation order,
  not ad-hoc special cases scattered through the codebase.
- **Auditable**: every decision is appended to a local, readable audit log.

## Request model

Every mediated action is represented as a `GuardrailRequest`:

- `direction`: `INBOUND` (something a peer is asking of/sending to us) or
  `OUTBOUND` (something this device is about to do or send).
- `actionType`: e.g. `RECEIVE_FILE`, `SHARE_LOCATION`, `READ_CONTACTS`,
  `ADVERTISE_CAPABILITY`, `RELAY_MESSAGE`, `LEAVE_MESH` (attempt to reach the
  internet).
- `peer`: the peer identity involved (if any), including its trust state
  (unverified / verified) and currently granted capabilities.
- `attributes`: action-specific data needed to evaluate rules (e.g. file
  name, capability being requested).

## Decision model

Evaluation of a `GuardrailRequest` produces one of:

- `Allow` — the action proceeds. Still logged.
- `Deny(reason)` — the action is blocked. `reason` is a plain-language
  string suitable for direct display to the user.
- `AskUser(reason, options)` — the action is paused pending an explicit
  user choice; the choice may optionally be remembered as a new rule.

There is no fourth option. Anything the policy set doesn't recognize
evaluates to `Deny` by construction (default deny), not to `Allow`.

## Evaluation order

0. **Per-peer inbound rate limit** — a fixed budget of inbound requests per
   peer per time window (default: 50 per 10 seconds), enforced ahead of
   everything else and, like the safety floor, not user-configurable. This
   exists so a flood costs nothing beyond an immediate `Deny` — no rule
   evaluation, and critically no `AskUser` prompt. Outbound actions are
   never rate limited: they're this device's own choice, with no one else
   to protect it from. See `RateLimiter` and docs/THREAT_MODEL.md #6.
1. **Hard-coded safety floor** — a small set of rules that cannot be
   disabled by user configuration (e.g. "never allow shell-like or code
   execution actions"). Checked first among policy proper; a match here
   always short-circuits to `Deny`.
2. **User-defined rules**, evaluated in priority order (most specific /
   most recently added first). The first matching rule decides the
   outcome.
3. **Category default** — if no rule matches, fall back to the default
   posture for that `actionType`'s category (sensitive categories default
   to `Deny`, informational/low-risk categories may default to `Allow`,
   configured per category, not per action).

Only one rule ever "wins" per request; there is no merging of partial
allows. Every stage still writes exactly one audit log entry per request —
rate-limited, floor-denied, and normally-decided requests are all equally
visible in the log.

## User-facing rules (`PolicyCatalog`)

What's actually shipped, each off by default unless noted, in
`shared/guardrail/PolicyCatalog.kt`:

- Auto-accept connections from people I've verified. *(on by default)*
- Only accept files from people I've verified. *(on by default)*
- Ignore messages from people I haven't verified.
- Let me share my location, asking every time.
- Help carry other people's messages. *(on by default; not yet acted on —
  relay isn't implemented, see README "Not built yet")*
- Only share lists with people I have verified.
- Only share status updates with people I have verified.

Plus the hard-coded floor and rate limit above, which aren't in this
catalog because the user cannot turn them off.

## Audit log

Every evaluated request is appended to a local, append-only, human-readable
audit log entry containing: timestamp, direction, action type, peer
identity (if any), decision, and the reason string. The log is for the
device owner; it is never transmitted off-device by default (transmitting
it would itself be a `GuardrailRequest`).

## Status

Implemented. `DefaultGuardrailEngine` (`shared/guardrail/`) is this design
as shipped, including the rule evaluator, per-peer rate limiting, and a
durable audit log (`FileAuditLog`); the Rules screen in the Android app
lets a user toggle each `PolicyCatalog` entry and read the audit log. See
docs/CHANGELOG.md for the order features landed in.
