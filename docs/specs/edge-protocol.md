# MateClaw Edge Protocol v1.0

Canonical wire format between Control Plane, Native Host, and Extension.
JSON over Chrome Native Messaging (Extension ↔ Native Host) and JSON-text-frame
over WebSocket (Native Host ↔ Control Plane).

## Envelope

Every message carries the same envelope:

```json
{
  "v": 1,
  "msg_id": "uuid-v4",
  "kind": "string (see EdgeMessageKind)",
  "ts": 1730000000123,
  "trace_id": "uuid-v4 (propagated end-to-end)",
  "session_id": "string (server-issued on hello, opaque to clients)",
  "in_reply_to": "msg_id (optional, for request/response correlation)",
  "payload": { /* per-kind */ }
}
```

- `v`: protocol version; receiver MUST close connection with code 4400 if v is unknown.
- `msg_id`, `trace_id`: UUID v4, lowercase, no braces.
- `ts`: epoch milliseconds, sender's wall clock.
- `session_id`: **Native Host is the sole owner of this field.** Empty string
  on the very first `hello` from Native Host; Control Plane assigns it in the
  `hello.ack` response and the Native Host stamps it on every subsequent
  outbound message. Clients downstream of Native Host (Chrome Extension,
  sidepanel UI) MUST emit empty string; Native Host MUST **override** any
  non-empty session_id it receives from stdin (defence against compromised
  extension trying to address another session).

## EdgeMessageKind (Phase 1 subset)

| kind | direction | payload |
|---|---|---|
| `hello` | NH → CP | `{ "agent_version": "string", "os": "string", "arch": "string", "auth": { "scheme": "jwt\|pat", "token": "string" } }` |
| `hello.ack` | CP → NH | `{ "session_id": "string", "server_version": "string", "heartbeat_interval_ms": 10000 }` |
| `heartbeat` | NH → CP | `{}` |
| `heartbeat.ack` | CP → NH | `{}` |
| `ping` | Ext → SW → NH → CP | `{ "echo": "string" }` |
| `pong` | CP → NH → SW → Ext | `{ "echo": "string", "server_ts": 1730000000123 }` |
| `error` | any direction | `{ "code": "string", "message": "string", "retryable": boolean }` |

Future kinds (Phase 2+, listed only for forward-compatibility — receivers must
ignore unknown kinds with a warning, not close the connection):

| kind | direction |
|---|---|
| `action.execute` | CP → NH → Ext |
| `action.result` | Ext → NH → CP |
| `event.page` | Ext → NH → CP |
| `event.network` | Ext → NH → CP |
| `event.risk` | Ext → NH → CP |
| `session.snapshot` | NH → CP |

## Auth failures and error codes

Auth failures are split into two distinct planes:

**Handshake-time (before WS upgrade completes — returned as HTTP status):**

| HTTP | Meaning |
|---|---|
| 401 | No `Authorization: Bearer …` header, or token does not parse/validate as either JWT or PAT. |
| 403 | Authenticated but unauthorised. Reserved for Phase-3 scope enforcement (`browser:edge`). Not emitted in Phase 1. |

**Post-upgrade (after WS is established — returned as WS close codes):**

| Close code | Meaning |
|---|---|
| 4400 | Protocol violation: unknown `v`, unparseable JSON envelope. |
| 4401 | Authentication revoked **mid-session** (token rotated, user disabled, PAT revoked). Reserved; not implemented in Phase 1 — added when a periodic revalidation job lands. |
| 4408 | Session timeout — Control Plane has not seen a heartbeat within the grace window. |
| 4409 | Conflicting session — same subject reconnected; old connection dropped. |
| 1000 | Normal closure (graceful shutdown either side). |
| 1011 | Server internal error — never sent deliberately; surfaces if a handler crashes. |

**In-band application errors (sent as an `error` envelope, connection stays open):**

| code | Meaning |
|---|---|
| `app.invalid_session` | The message's `session_id` is not known to the server. |
| `app.session_binding_mismatch` | The `session_id` is known, but the calling socket or principal does not own it. Logged at WARN — possible abuse signal. |
| `app.rate_limited` | Too many connections from the same auth principal (Phase 3). |

## Heartbeat & timeout

- Native Host sends `heartbeat` every 10s (default; the server-issued value in
  `hello.ack.heartbeat_interval_ms` overrides this for the connection).
- Control Plane responds `heartbeat.ack` immediately.
- If Control Plane sees no heartbeat for 30s (3× interval), it MUST close
  the connection with 4408 and remove the session from the registry.
- If Native Host sees no `heartbeat.ack` for 3× interval, it MUST close
  the connection (typed error `ErrHeartbeatTimeout`); the runner-layer
  reconnect loop then attempts to reconnect with exponential backoff
  starting at 1s, capped at 60s with ±20% jitter, up to 5 consecutive
  failures before exiting.

## Reconnect & resumption

- Phase 1: no resumption. On reconnect, Native Host sends a fresh `hello` and
  receives a brand new `session_id`. Outbox messages with the old `session_id`
  are dropped.
- Phase 2+ will add `hello.resume` with `last_session_id` + `cursor`.

## Forward-compatibility invariant

A receiver MUST silently log-and-drop any envelope whose `kind` is not in
its known set (subject to v matching). Unknown kinds MUST NOT close the
connection. This keeps Phase 1 receivers compatible with Phase 2+ senders
that add new `action.*` / `indicator.*` / `a11y.*` kinds.
