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
- `origin`: the peer identity involved (if any), including its trust state
  (unverified / verified) and currently held capabilities.
- `attributes`: action-specific data needed to evaluate rules (e.g. file
  size, capability being requested, destination).

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

1. **Hard-coded safety floor** — a small set of rules that cannot be
   disabled by user configuration (e.g. "never allow shell-like or code
   execution actions"). Checked first; a match here always short-circuits
   to `Deny`.
2. **User-defined rules**, evaluated in priority order (most specific /
   most recently added first). The first matching rule decides the
   outcome.
3. **Category default** — if no rule matches, fall back to the default
   posture for that `actionType`'s category (sensitive categories default
   to `Deny`, informational/low-risk categories may default to `Allow`,
   configured per category, not per action).

Only one rule ever "wins" per request; there is no merging of partial
allows.

## Example user-facing rules

- Only accept files from previously verified people.
- Never allow access to contacts, calendar, or precise location without
  asking.
- Maximum receivable file size: `N` MB.
- Never allow shell-like or code execution actions. *(hard-coded floor,
  not user-editable)*
- Always ask before sharing location.
- Only advertise capabilities I've explicitly enabled.
- Block any request that tries to leave the local mesh without permission.

## Audit log

Every evaluated request is appended to a local, append-only, human-readable
audit log entry containing: timestamp, direction, action type, peer
identity (if any), decision, and the reason string. The log is for the
device owner; it is never transmitted off-device by default (transmitting
it would itself be a `GuardrailRequest`).

## Status

This is the design for Phase 2. Phase 0 ships only the `GuardrailEngine`
interface and request/decision types in `shared/guardrail/`; the rule
evaluator, audit log persistence, and UI live in later phases.
