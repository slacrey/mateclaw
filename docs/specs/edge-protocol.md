# MateClaw Edge Protocol v1.2

Canonical wire format between Control Plane, Native Host, and Extension.
JSON over Chrome Native Messaging (Extension ↔ Native Host) and JSON-text-frame
over WebSocket (Native Host ↔ Control Plane).

**v1.1 (Phase 2 P-stream)** adds atomic browser actions, visual indicators,
accessibility-tree snapshots, and unsolicited page-lifecycle events.

**v1.2 (Phase 3 T3.2)** adds screenshot capture request/response kinds for
VisionEngine's base64 PNG input channel. The envelope is unchanged; older
receivers continue to forward-compat ignore the new kinds, so this bump is
fully backward-compatible.

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

## EdgeMessageKind — v1.0 base

Handshake + liveness; established in Phase 1. Unchanged in v1.1.

| kind | direction | payload |
|---|---|---|
| `hello` | NH → CP | `{ "agent_version": "string", "os": "string", "arch": "string", "auth": { "scheme": "jwt\|pat", "token": "string" } }` |
| `hello.ack` | CP → NH | `{ "session_id": "string", "server_version": "string", "heartbeat_interval_ms": 10000 }` |
| `heartbeat` | NH → CP | `{}` |
| `heartbeat.ack` | CP → NH | `{}` |
| `ping` | Ext → SW → NH → CP | `{ "echo": "string" }` |
| `pong` | CP → NH → SW → Ext | `{ "echo": "string", "server_ts": 1730000000123 }` |
| `error` | any direction | `{ "code": "string", "message": "string", "retryable": boolean }` |

## EdgeMessageKind — v1.1 additions (Phase 2 P-stream)

Thirteen new kinds across four families. Every envelope addressed at a
specific tab carries a `tab_ref` field — see [TabRef](#tabref) below.

| kind | direction | summary |
|---|---|---|
| `action.execute` | CP → NH → Ext | request to perform an atomic action |
| `action.result` | Ext → NH → CP | success or typed error |
| `action.cancel` | CP → NH → Ext | cancel an in-flight action by `in_reply_to` |
| `indicator.show` | CP → NH → Ext | show cursor + glow + stop button |
| `indicator.hide` | CP → NH → Ext | hide everything |
| `indicator.cursor` | CP → NH → Ext | move phantom cursor; result on `action.result` |
| `indicator.tool_use_hide` | CP → NH → Ext | hide all overlays for a screenshot |
| `indicator.tool_use_show` | CP → NH → Ext | restore prior visibility |
| `indicator.stop_clicked` | Ext → NH → CP | user clicked stop button |
| `a11y.snapshot.request` | CP → NH → Ext | request accessibility tree |
| `a11y.snapshot.response` | Ext → NH → CP | tree + viewport metadata |
| `event.page.navigated` | Ext → NH → CP | tab navigated (page loaded) |
| `event.tab.closed` | Ext → NH → CP | tab the session was using was closed |

## EdgeMessageKind — v1.2 additions (Phase 3 T3.2)

Two new kinds carry screenshot capture requests and base64 PNG responses.

| kind | direction | summary |
|---|---|---|
| `screenshot.capture.request` | CP → NH → Ext | request a base64 PNG of the current tab |
| `screenshot.capture.response` | Ext → NH → CP | base64 PNG payload + viewport metadata |

### TabRef

`tab_ref` is the connection between a v1.1 envelope and a physical Chrome
tab. Three forms accepted on the wire:

```
"main"             → SW resolves via TabGroupManager.getMainTabId(sessionSubject)
"active"           → SW resolves via chrome.tabs.query({active:true, lastFocusedWindow:true})
<integer>          → explicit Chrome tab id (used for tests and future multi-tab orchestration)
```

If neither `"main"` nor `"active"` resolution succeeds, the SW emits
`action.result` / `a11y.snapshot.response` / `screenshot.capture.response`
Failure with `code: "NO_TARGET_TAB"`.

On the Java side, `TabRef` is a sealed interface (`TabRef.Main`,
`TabRef.Active`, `TabRef.Explicit(long tabId)`) with a custom Jackson
serializer/deserializer — JSON-value shape (string vs. number) is the
discriminator, since neither `NAME` nor `DEDUCTION` can express
string-or-number on a single field.

### v1.1 payload shapes

**`action.execute`** (CP → NH → Ext):
```json
{
  "tab_ref": "main",
  "kind": "navigate",
  "params": { /* per-action; see Action payload schemas below */ },
  "deadline_ms": 30000
}
```

**`action.result`** (Ext → NH → CP):
```json
// Success:
{ "ok": true, "elapsed_ms": 12, "payload": { /* per-action success */ } }

// Failure:
{ "ok": false, "code": "TIMEOUT_PAGE_LOAD", "message": "...", "retryable": true }
```

Standard error codes: `TIMEOUT_PAGE_LOAD`, `GROUNDING_AMBIGUOUS`,
`NO_TARGET_TAB`, `CANCELLED`, `DEADLINE_EXCEEDED`, `SESSION_DETACHED`,
`DEVTOOLS_OPEN`.

**`action.cancel`** (CP → NH → Ext):
```json
{ "tab_ref": "main", "reason": "user_stop" }
```
`reason` is one of `user_stop` | `timeout` | `deadline_exceeded`.

**`indicator.show` / `indicator.hide` / `indicator.tool_use_hide` / `indicator.tool_use_show`**:
```json
{ "tab_ref": "main", "is_mcp": false }
```
`is_mcp` is only valid on `indicator.show`; controls a corner badge variant.

**`indicator.cursor`**:
```json
// Request:
{ "tab_ref": "main", "x": 540, "y": 320 }
// Result (on action.result):
{ "ok": true, "arrived_at_ms": 1730000000123 }
```

**`indicator.stop_clicked`** (Ext → CP):
```json
{ "tab_ref": 42 }
```
The Extension sends `session_id: ""` (NH stamps the real id). CP locates
the in-flight action by `sessionId` lookup, not by any correlation id in
this envelope.

**`a11y.snapshot.request`**:
```json
{
  "tab_ref": "main",
  "filter": "interactive",
  "depth": 15,
  "max_chars": 200000,
  "ref_id": "ref_42"
}
```
`filter` is one of `interactive` | `all` | `default`. `ref_id` is optional;
when present, the response includes only the subtree rooted at that ref.

**`a11y.snapshot.response`**:
```json
{
  "snapshot_id": "snap-uuid",
  "captured_at_ms": 1730000000123,
  "tab_ref": 42,
  "tree": "Button[ref=ref_1]: Submit\n...",
  "viewport": { "w": 1280, "h": 800 }
}
```
`tab_ref` is **echoed as the resolved tab id** so the CP can update its
freshness map keyed by absolute tab id.

### v1.2 payload shapes

**`screenshot.capture.request`**:
```json
{
  "tab_ref": "main",
  "format": "png",                  // future: webp / jpeg with quality
  "quality": 90,                    // ignored for png; spec for future jpeg
  "scale_factor": 1                 // 1 = native pixel; >1 = downscale for vision LLM token budget
}
```

**`screenshot.capture.response`** (success):
```json
{
  "snapshot_id": "shot-uuid",
  "captured_at_ms": 1730000000123,
  "tab_ref": 42,                    // resolved tab id echoed
  "format": "png",
  "data_base64": "iVBORw0KGgoAAAANS...",  // ≤500 KB encoded; SW must reject larger
  "viewport": { "w": 1280, "h": 800 },
  "actual_dimensions": { "w": 1280, "h": 800 }  // post-scale_factor
}
```

**`screenshot.capture.response`** (failure shape — same envelope, different keys):
```json
{
  "snapshot_id": "shot-uuid",
  "captured_at_ms": 1730000000123,
  "tab_ref": -1,
  "error": {
    "code": "NO_TARGET_TAB | SCREENSHOT_TOO_LARGE | PERMISSION_DENIED",
    "message": "..."
  }
}
```

Screenshot capture error codes:

| code | Meaning |
|---|---|
| `NO_TARGET_TAB` | `tab_ref` resolution failed |
| `SCREENSHOT_TOO_LARGE` | Base64 payload exceeded 500 KB; Native Messaging has a 1 MB frame cap, so half is reserved for envelope overhead |
| `PERMISSION_DENIED` | `chrome.tabs.captureVisibleTab` failed on a restricted page such as `chrome://` |

**`event.page.navigated`** (Ext → CP):
```json
{ "tab_ref": 42, "url": "https://example.com/new-page" }
```

**`event.tab.closed`** (Ext → CP):
```json
{ "tab_ref": 42 }
```

### Per-action success schemas (payload of `action.result.payload`)

| `action.kind` | `payload` fields on success |
|---|---|
| `navigate` | `{ final_url: string, http_status?: int, load_state: "load"\|"domcontentloaded"\|"network_idle" }` |
| `click` | `{ }` (empty — caller infers state via subsequent snapshot) |
| `type` | `{ chars_typed: int }` |
| `scroll` | `{ }` |
| `move_mouse` | `{ arrived_at_ms: int, waypoints: int }` (waypoints≥1; `natural` profile emits N≥5) |
| `wait` | `{ waited_ms: int }` |

### A11y snapshot lifecycle

The Control Plane maintains a per-`(sessionId, resolvedTabId)` freshness
map for accessibility snapshots:

```
SnapshotState = { snapshot_id, captured_at_ms, status: FRESH | SUSPECT | STALE }
```

Status transitions:

| Trigger | New status |
|---|---|
| Fresh `a11y.snapshot.response` arrives | FRESH |
| `action.result` for `navigate` succeeds | STALE (ref_N invalidated by URL change) |
| `action.result` for `click` / `type` / `scroll` succeeds | SUSPECT (one retry budget — if next ground misses, refresh) |
| Snapshot age > 30 s | STALE |
| `event.tab.closed` for this tab | (entry removed) |
| `event.page.navigated` arrives (in-page nav) | STALE |

`PageSnapshotService.request()` (task F5) auto-refreshes on STALE; on
SUSPECT it serves the cached copy once, demoting to STALE after a failed
ground attempt.

## Future kinds (Phase 3+)

Listed only for forward-compatibility — receivers must ignore unknown kinds
with a warning, not close the connection:

| kind | direction |
|---|---|
| `event.network` | Ext → NH → CP |
| `event.risk` | Ext → NH → CP |
| `session.snapshot` | NH → CP |
| `hello.resume` | NH → CP |

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
its known set (subject to `v` matching). Unknown kinds MUST NOT close the
connection. This invariant keeps v1.0 receivers compatible with v1.1
senders, and keeps v1.1 receivers compatible with v1.2/Phase 3+ senders.

The three runtime mirrors (`EdgeMessageKind.java`,
`mateclaw-browser-bridge/src/internal/edgeproto/edgeproto.ts`,
`mateclaw-extension/src/shared/edge-protocol.ts`) all collapse unknown
kinds to the `__unknown__` sentinel rather than rejecting the envelope.
