# Browser Agent Foundation (Phase 1 / W1) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
>
> **For Codex (review pass):** This plan defines the *foundation* of MateClaw's Browser Agent system: three-tier infrastructure (Java Control Plane ↔ Go Native Host ↔ Chrome MV3 Extension) connected over mTLS WSS + Native Messaging, ending in an end-to-end structured `ping/pong` that proves auth, session registry, heartbeat, and stdio bridging all work. Phase 2+ (CDP actions, three-engine grounding, SOP, recovery matrix) layer on top — none of them works if this foundation is wrong. Please review for: TDD discipline, protocol consistency across three languages, security (auth/mTLS), idempotency of the session registry, error handling at every boundary, and whether each task is genuinely "bite-sized".

> **Revision history**
> - 2026-05-28 v1.0 — initial draft.
> - 2026-05-28 v1.1 — applied Codex audit response (see
>   `2026-05-28-browser-agent-foundation.audit-response.md`). Eight findings
>   accepted; mTLS deferred to Phase 3; session_id ownership clarified;
>   registry race fixed; auth APIs corrected; validSession now binding-aware;
>   heartbeat ack monitoring and reconnect loop added; D3 stub replaced;
>   C6/C7/SQLite outbox moved out of Phase 1 scope.
> - 2026-05-28 v1.2 — **B-stream (Native Host) pivoted from Go to Node.js +
>   TypeScript**. The system the plan ships on lacks a Go toolchain (and
>   the `-race` test requirement adds a transitive GCC dependency on
>   Windows). Architecture is **identical** — same `Client` /
>   `Runner` / `Inbound() channel` / heartbeat monitor / reconnect with
>   exponential backoff / single reader on the WS / Native-Host-stamps-
>   `session_id` invariant — only the implementation language changes.
>   Stack: TypeScript 5 + Node 20 + `ws` + `vitest`; distributed via
>   `bun build --compile` to a single `bridge.exe` (eliminates the runtime
>   Node dependency for end users). All audit findings P0-1 (session_id
>   stamp), P1-5 (heartbeat ack), B7 reconnect loop and the single-reader
>   contract transfer to the TS implementation verbatim. The B-stream
>   tasks B1–B7 in this document remain the authoritative architectural
>   contract; the implementer maps each Go API to its TS equivalent.

**Goal:** Stand up the three-process infrastructure (Control Plane / Native Host / Extension) and prove an end-to-end structured ping flows Sidepanel → Service Worker → Native Messaging → Native Host → WSS → Control Plane → ack back. The single connection between Native Host and Control Plane is TLS-protected via WSS (in production behind a reverse proxy terminating TLS 1.3); authentication is Bearer (JWT or PAT). **mTLS is explicitly Phase 3 scope** — see Out-of-scope below.

**Architecture:**
- **Control Plane** (existing `mateclaw-server`, Java 21 / Spring Boot 3.5): adds a new package `vip.mate.browser.edge` providing a WebSocket endpoint `/api/v1/browser/edge`, an in-memory `BrowserSessionRegistry`, JWT/PAT Bearer authentication on the handshake, and a `ping/pong` handler. No database schema changes in Phase 1 — registry stays in-memory until Phase 4 (state machine + checkpoint).
- **Native Host** (new sibling project `mateclaw-browser-bridge/`, Go 1.22): long-running user-machine daemon. **Bearer-over-WSS client** to Control Plane; Native Messaging stdio server for Chrome Extension. Single static binary per OS. **Owns the server-issued `session_id`** — clients downstream (Extension) never set it. (SQLite outbox is *not* in Phase 1; it joins in Phase 4 when stateful work needs to survive a CP outage.)
- **Extension** (new sibling project `mateclaw-extension/`, TypeScript 5 / Vue 3 / Vite, MV3): manifest, Service Worker (Native Messaging client), Sidepanel UI. Always emits `session_id=""` on outbound messages — Native Host stamps the real id. Communicates with Native Host via Chrome `runtime.connectNative`. (Offscreen Document for SW-kill survival is Phase 2 work, deferred together with `chrome.debugger.attach`.)

**Tech Stack:**
- Java 21 · Spring Boot 3.5.14 · Spring WebSocket · JUnit 5 · Mockito · MateClaw existing modules (`mateclaw-server`)
- Go 1.22 · `nhooyr.io/websocket` · Go standard `testing` (SQLite deferred to Phase 4)
- TypeScript 5 · Vue 3 · Vite 6 · Vitest · Chrome MV3 APIs (`chrome.runtime`, `chrome.runtime.connectNative`, `chrome.sidePanel`). The `chrome.offscreen` API joins in Phase 2.
- All three sides share a single canonical message schema defined in `docs/specs/edge-protocol.md` (created in Task A1).

**Out of scope for this plan (handed off to later phases):**
- `chrome.debugger.attach` and any CDP-level browser control → **Phase 2**
- DOM / A11y / Vision engines and Orchestrator → **Phase 2**
- Offscreen Document for SW-kill survival (Chrome Extension `offscreen` API) → **Phase 2** (only needed once CDP work is long-running enough to outlive the SW lifetime)
- SOP YAML, Synthesizer, Replay, Drift, Adapter → **Phase 3**
- Workflow integration, AI nodes, Risk Runtime → **Phase 3**
- Database persistence of sessions, SQLite outbox in Native Host → **Phase 4** (state machine + checkpoint)
- **mTLS** / client certificate issuance / cert rotation → **Phase 3** (SaaS hardening). Phase 1 uses Bearer-over-WSS; production TLS is terminated at a reverse proxy in front of the JVM.
- Per-scope authorisation (`browser:edge`) — PAT entity carries a `scopes` field but server-side scope enforcement is a separate RFC. Phase 1 accepts any active PAT/JWT for this endpoint.
- Production auto-update of Native Host → **Phase 3**

---

## Codex review hot-spots

When auditing this plan, please pay special attention to:

1. **Protocol consistency** — `EdgeMessage` fields and `EdgeMessageKind` values must match exactly across Java / Go / TypeScript. Drift between languages is the #1 failure mode in cross-process systems.
2. **Authentication boundaries** — Handshake-time auth failures return **HTTP 401** (missing/bad Bearer) or **HTTP 403** (insufficient scope, when scope enforcement lands in Phase 3). WS close code **4401** is reserved for post-upgrade auth loss (token revoked mid-session). Phase 1 does **not** implement post-upgrade revocation; it reserves the code only.
3. **session_id ownership** — Native Host is the *sole* owner of the server-issued `session_id`. Extension always sends empty string; Native Host stamps the real id on forward. NH **overrides** any non-empty session_id from stdin (defence against compromised extension).
4. **Session binding** — `validSession` MUST check (a) the message's session_id exists, (b) the session's `ws.id` matches the *current* socket, (c) the session's subject matches the authenticated principal. Failing any of these returns `app.session_binding_mismatch`. Verify the test exercises each binding failure path.
5. **Registry concurrency** — `register()` must be atomic across `byId` and `subjectToSession`. The fix uses `ConcurrentHashMap.compute` on `subjectToSession` to serialise per-subject; verify the concurrency test asserts `byId.size() == 1` under N concurrent registrations for the same subject.
6. **Heartbeat lifecycle** — Native Host tracks `lastAckAt` and fails fast with `ErrHeartbeatTimeout` after 3× interval of silence. Reconnect with exponential backoff (1s→60s, ±20% jitter, max 5 attempts) belongs to the runner layer, not the WS client. Verify both are tested.
7. **TDD discipline** — Every implementation task must have its test written and *seen failing* before the implementation step. If you see "write test + implement + commit" without an explicit "run test, see it fail" step, flag it.
8. **Bite-sized tasks** — Each step should be 2–5 minutes. Flag any step that obviously needs to be split.
9. **No premature abstractions** — Phase 1 is foundation. If you see a generic registry, abstract base classes, or "we'll need this later" code, push back.

---

## Project conventions reminder (for context)

- MateClaw uses `${revision}` (currently `1.4.0`) via `flatten-maven-plugin`. Do **not** hard-code version in any child POM.
- Java tests under `mateclaw-server/src/test/java/vip/mate/<package>/`. Surefire is pre-configured with byte-buddy agent for Mockito on JDK 21.
- New Java packages live under `vip.mate.browser` to keep blast radius small from existing modules.
- Conventional Commits (`feat:`, `fix:`, `chore:`, `test:`, `docs:`).
- All commits include the trailer:
  ```
  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  ```
- Branch: work on a new feature branch `feat/browser-foundation` off `dev`.

---

## Workstream overview

```
A. Control Plane (Java)            B. Native Host (Go)        C. Extension (TypeScript)
   A1 Edge protocol spec              B1 Project scaffolding      C1 Project scaffolding
   A2 EdgeMessage + Kind enum         B2 Config loading            C2 MV3 manifest
   A3 BrowserSessionRegistry          B3 Edge protocol types       C3 EdgeMessage TS mirror
   A4 Auth interceptor                B4 WSS client + hello        C4 SW NativeBridge
   A5 WebSocket handler               B5 Heartbeat monitor         C5 Sidepanel UI (Vue 3)
   A6 WebSocketConfig wiring          B6 NM stdio codec
   A7 Session reaper                  B7 Runner (NM ↔ WSS) +
                                          reconnect loop +
                                          session_id stamp

                    D. Integration & end-to-end
                       D1 Native Host install manifests (Win/Mac/Linux)
                       D2 End-to-end smoke runbook
                       D3 Sessions debug endpoint (real registry snapshot)
```

Dependencies: **A1 ⟶ A2 ⟶ everything**. Workstreams B and C can proceed in parallel after A2. D requires A6, A7, B7, C5.

**Removed from Phase 1** (vs. the v1.0 draft):
- SQLite outbox in NH (was B5) → Phase 4 (state machine + checkpoint).
- Offscreen Document WSS fallback (was C6) → Phase 2 (paired with `chrome.debugger.attach`).
- Standalone "SW ↔ Sidepanel bus" task (was C7) → folded into C4 (NativeBridge) and C5 (sidepanel App); no separate task.

---

# Task A1: Edge Protocol Specification

**Files:**
- Create: `docs/specs/edge-protocol.md`

This is a *documentation-first* task. The spec is the single source of truth that A2–A6, B3, and C5 all derive from. Codex should especially audit this spec for completeness — every later task quotes it.

**Step 1: Write the spec document**

Create `docs/specs/edge-protocol.md` with:

````markdown
# MateClaw Edge Protocol v1

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
| `hello` | NH → CP | `{ "agent_version": "string", "os": "string", "arch": "string", "auth": { "scheme": "jwt|pat", "token": "string" } }` |
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

- Native Host sends `heartbeat` every 10s.
- Control Plane responds `heartbeat.ack` immediately.
- If Control Plane sees no heartbeat for 30s, it MUST close the connection with
  4408 and remove the session from the registry.
- If Native Host sees no `heartbeat.ack` for 30s, it MUST close the connection
  and enter reconnect with exponential backoff starting at 1s, capped at 60s.

## Reconnect & resumption

- Phase 1: no resumption. On reconnect, Native Host sends a fresh `hello` and
  receives a brand new `session_id`. Outbox messages with the old `session_id`
  are dropped.
- Phase 2+ will add `hello.resume` with `last_session_id` + `cursor`.
````

**Step 2: Commit the spec**

```bash
git checkout -b feat/browser-foundation
git add docs/specs/edge-protocol.md
git commit -m "$(cat <<'EOF'
docs: add Browser Edge protocol v1 spec

Defines the canonical message envelope, EdgeMessageKind enum,
auth/error close codes, and heartbeat rules shared by Java
Control Plane, Go Native Host, and Chrome Extension.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task A2: EdgeMessage value class + EdgeMessageKind enum

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/protocol/EdgeMessage.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/protocol/EdgeMessageKind.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/protocol/EdgeMessageTest.java`

**Step 1: Write the failing test**

Create `mateclaw-server/src/test/java/vip/mate/browser/edge/protocol/EdgeMessageTest.java`:

```java
package vip.mate.browser.edge.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialise_roundtrip_preservesAllFields() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1)
                .msgId("00000000-0000-0000-0000-000000000001")
                .kind(EdgeMessageKind.PING)
                .ts(1730000000123L)
                .traceId("00000000-0000-0000-0000-000000000002")
                .sessionId("sess-abc")
                .payload(Map.of("echo", "hello"))
                .build();

        String json = mapper.writeValueAsString(msg);
        EdgeMessage back = mapper.readValue(json, EdgeMessage.class);

        assertThat(back.getV()).isEqualTo(1);
        assertThat(back.getMsgId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(back.getKind()).isEqualTo(EdgeMessageKind.PING);
        assertThat(back.getTs()).isEqualTo(1730000000123L);
        assertThat(back.getTraceId()).isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(back.getSessionId()).isEqualTo("sess-abc");
        assertThat(back.getPayload()).isEqualTo(Map.of("echo", "hello"));
    }

    @Test
    void kind_serialisesAsLowercaseDotted() throws Exception {
        EdgeMessage msg = EdgeMessage.builder()
                .v(1).msgId("x").kind(EdgeMessageKind.HELLO_ACK).ts(0L).traceId("x").sessionId("").build();
        String json = mapper.writeValueAsString(msg);
        assertThat(json).contains("\"kind\":\"hello.ack\"");
    }

    @Test
    void kind_deserialiseUnknown_yieldsUnknown() throws Exception {
        String json = """
            {"v":1,"msg_id":"x","kind":"future.thing","ts":0,"trace_id":"x","session_id":""}
            """;
        EdgeMessage msg = mapper.readValue(json, EdgeMessage.class);
        assertThat(msg.getKind()).isEqualTo(EdgeMessageKind.UNKNOWN);
    }
}
```

**Step 2: Run the test to verify it fails**

Run: `cd mateclaw-server && mvn test -Dtest=EdgeMessageTest`
Expected: `COMPILATION ERROR` — classes don't exist.

**Step 3: Implement `EdgeMessageKind.java`**

```java
package vip.mate.browser.edge.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EdgeMessageKind {
    HELLO("hello"),
    HELLO_ACK("hello.ack"),
    HEARTBEAT("heartbeat"),
    HEARTBEAT_ACK("heartbeat.ack"),
    PING("ping"),
    PONG("pong"),
    ERROR("error"),
    /** Unknown wire kind. Forward-compat: receivers warn-and-drop, do not close. */
    UNKNOWN("__unknown__");

    private final String wire;

    EdgeMessageKind(String wire) { this.wire = wire; }

    @JsonValue
    public String wire() { return wire; }

    @JsonCreator
    public static EdgeMessageKind fromWire(String s) {
        if (s == null) return UNKNOWN;
        for (EdgeMessageKind k : values()) {
            if (k.wire.equals(s)) return k;
        }
        return UNKNOWN;
    }
}
```

**Step 4: Implement `EdgeMessage.java`**

```java
package vip.mate.browser.edge.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Canonical envelope shared between Control Plane, Native Host, and Extension.
 * See docs/specs/edge-protocol.md for the wire contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EdgeMessage {

    /** Protocol version. Receivers MUST close with 4400 on unknown v. */
    private int v;

    @JsonProperty("msg_id")
    private String msgId;

    private EdgeMessageKind kind;

    /** Sender wall-clock epoch millis. */
    private long ts;

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("in_reply_to")
    private String inReplyTo;

    /** Kind-specific payload. Schema documented per-kind in the spec. */
    private Map<String, Object> payload;
}
```

**Step 5: Run test, confirm pass, commit**

Run: `cd mateclaw-server && mvn test -Dtest=EdgeMessageTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

```bash
git add mateclaw-server/src/main/java/vip/mate/browser/edge/protocol/ \
        mateclaw-server/src/test/java/vip/mate/browser/edge/protocol/
git commit -m "$(cat <<'EOF'
feat(browser): add EdgeMessage envelope and EdgeMessageKind enum

Canonical wire format for the Browser Agent foundation, shared
across Java/Go/TS. Unknown kinds map to UNKNOWN to preserve
forward-compat per spec v1.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task A3: BrowserSessionRegistry (in-memory, thread-safe)

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/session/BrowserSession.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/session/BrowserSessionRegistry.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/session/BrowserSessionRegistryTest.java`

**Step 1: Write failing tests for the registry**

```java
package vip.mate.browser.edge.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserSessionRegistryTest {

    private BrowserSessionRegistry registry;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.ofEpochMilli(1_730_000_000_000L), ZoneOffset.UTC);
        registry = new BrowserSessionRegistry(fixedClock);
    }

    @Test
    void register_returnsSessionWithGeneratedId() {
        WebSocketSession ws = mockWs("ws-1");
        BrowserSession s = registry.register("alice", ws, "1.0.0");

        assertThat(s.getId()).isNotBlank();
        assertThat(s.getSubject()).isEqualTo("alice");
        assertThat(s.getAgentVersion()).isEqualTo("1.0.0");
        assertThat(s.getLastHeartbeatAt()).isEqualTo(Instant.ofEpochMilli(1_730_000_000_000L));
    }

    @Test
    void register_sameSubject_replacesPreviousSession() {
        WebSocketSession ws1 = mockWs("ws-1");
        WebSocketSession ws2 = mockWs("ws-2");
        BrowserSession s1 = registry.register("alice", ws1, "1.0.0");
        BrowserSession s2 = registry.register("alice", ws2, "1.0.0");

        assertThat(s2.getId()).isNotEqualTo(s1.getId());
        assertThat(registry.find(s1.getId())).isEmpty();          // old removed
        assertThat(registry.find(s2.getId())).isPresent();        // new active
        assertThat(registry.sizeForSubject("alice")).isEqualTo(1);
        // Old ws was closed with 4409
        verify(ws1).close(argThat(s -> s.getCode() == 4409));
    }

    @Test
    void heartbeat_updatesLastHeartbeatAt() {
        BrowserSession s = registry.register("alice", mockWs("ws"), "1.0.0");

        Clock later = Clock.fixed(Instant.ofEpochMilli(1_730_000_005_000L), ZoneOffset.UTC);
        registry.setClockForTest(later);
        registry.heartbeat(s.getId());

        assertThat(registry.find(s.getId()).orElseThrow().getLastHeartbeatAt())
                .isEqualTo(Instant.ofEpochMilli(1_730_000_005_000L));
    }

    @Test
    void reapStale_removesSessionsBeyondGrace() {
        BrowserSession s = registry.register("alice", mockWs("ws"), "1.0.0");

        // 31 seconds later — past the 30s grace
        Clock later = Clock.fixed(Instant.ofEpochMilli(1_730_000_031_000L), ZoneOffset.UTC);
        registry.setClockForTest(later);

        int reaped = registry.reapStale();
        assertThat(reaped).isEqualTo(1);
        assertThat(registry.find(s.getId())).isEmpty();
    }

    @Test
    void reapStale_keepsLiveSessions() {
        BrowserSession s = registry.register("alice", mockWs("ws"), "1.0.0");
        Clock later = Clock.fixed(Instant.ofEpochMilli(1_730_000_005_000L), ZoneOffset.UTC);
        registry.setClockForTest(later);

        assertThat(registry.reapStale()).isZero();
        assertThat(registry.find(s.getId())).isPresent();
    }

    // ─── Concurrency invariant (P1-3 fix) ─────────────────────────────────
    @Test
    void register_concurrentSameSubject_leavesExactlyOneLiveSession() throws Exception {
        int parallelism = 64;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism);
        var ready = new java.util.concurrent.CountDownLatch(parallelism);
        var go = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(parallelism);
        var sessionIds = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();

        for (int i = 0; i < parallelism; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    BrowserSession s = registry.register("alice", mockWs("ws-" + idx), "1.0.0");
                    sessionIds.add(s.getId());
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        go.countDown();             // unleash all threads at once
        done.await();
        pool.shutdown();

        // Invariant: exactly one live session for the subject.
        assertThat(registry.sizeForSubject("alice")).isEqualTo(1);
        // Invariant: byId.size() also == 1 (the bug Codex caught let this be > 1).
        assertThat(registry.size()).isEqualTo(1);
        // The surviving id is one of the N generated.
        var survivingId = registry.findBySubject("alice").orElseThrow().getId();
        assertThat(sessionIds).contains(survivingId);
    }

    private WebSocketSession mockWs(String id) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(id);
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }
}
```

**Step 2: Run the test, see it fail with compile errors**

Run: `cd mateclaw-server && mvn test -Dtest=BrowserSessionRegistryTest`
Expected: `cannot find symbol` for `BrowserSession`, `BrowserSessionRegistry`.

**Step 3: Implement `BrowserSession.java`**

```java
package vip.mate.browser.edge.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;

/**
 * Live, in-memory representation of one Browser Agent edge session.
 * One subject → at most one active session in Phase 1.
 *
 * <p>"Subject" carries either the JWT subject (username) or the PAT's
 * {@code userId.toString()}, depending on which auth path established the
 * session. Long-typed user identity is introduced in Phase 4 when sessions
 * become DB-persistent.
 */
@Data
@Builder
@AllArgsConstructor
public class BrowserSession {

    /** Server-issued opaque id, communicated to Native Host via hello.ack. */
    private final String id;

    /**
     * Authenticated principal — either JWT subject (username) or
     * PAT user id as string. Source of truth for the binding check
     * in {@link vip.mate.browser.edge.EdgeWebSocketHandler}.
     */
    private final String subject;

    /** Native Host agent version, from hello payload. */
    private final String agentVersion;

    /** Underlying WebSocket; do not leak outside the registry. */
    private final WebSocketSession ws;

    /** Last time we received any message (heartbeat or otherwise). */
    private volatile Instant lastHeartbeatAt;
}
```

**Step 4: Implement `BrowserSessionRegistry.java`**

```java
package vip.mate.browser.edge.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of live Browser Agent edge sessions.
 *
 * <p>Phase 1 is in-memory only — sessions die with the JVM. Phase 4 introduces
 * Postgres-backed session checkpointing and resumption.
 *
 * <p>Single-active-session-per-user policy: re-registering for the same user
 * replaces the previous session and closes the old WebSocket with code 4409.
 * This is intentional — a Native Host crash + reconnect must not leave a stale
 * handler holding the registry slot.
 */
@Slf4j
@Component
public class BrowserSessionRegistry {

    /** Heartbeat grace window: if no message seen for this long, session is stale. */
    public static final Duration STALE_GRACE = Duration.ofSeconds(30);

    private final ConcurrentHashMap<String, BrowserSession> byId = new ConcurrentHashMap<>();
    /** subject → live sessionId. compute() on this map is the per-subject serialisation lock. */
    private final ConcurrentHashMap<String, String> subjectToSession = new ConcurrentHashMap<>();

    private volatile Clock clock;

    public BrowserSessionRegistry() { this(Clock.systemUTC()); }

    public BrowserSessionRegistry(Clock clock) { this.clock = clock; }

    /** Test seam — do not call from production code. */
    void setClockForTest(Clock clock) { this.clock = clock; }

    /**
     * Register a new session for {@code subject}. Atomically replaces any
     * existing session for the same subject; the old socket is closed with
     * 4409 inside the per-subject compute() lock so two concurrent
     * registrations cannot leave two live entries in {@link #byId}.
     */
    public BrowserSession register(String subject, WebSocketSession ws, String agentVersion) {
        String newId = "sess-" + UUID.randomUUID();
        BrowserSession session = BrowserSession.builder()
                .id(newId)
                .subject(subject)
                .agentVersion(agentVersion)
                .ws(ws)
                .lastHeartbeatAt(clock.instant())
                .build();

        // (1) Publish in byId first so concurrent readers see a coherent state.
        byId.put(newId, session);

        // (2) Atomically remap subject → newId. Inside the closure we are the
        //     only writer for this subject; removing the previous id is race-free.
        subjectToSession.compute(subject, (k, existingId) -> {
            if (existingId != null && !existingId.equals(newId)) {
                BrowserSession prev = byId.remove(existingId);
                if (prev != null) {
                    closeQuietly(prev.getWs(), new CloseStatus(4409, "session-conflict"));
                    log.info("[edge] subject {} reconnected; dropped previous session {}",
                            subject, existingId);
                }
            }
            return newId;
        });
        return session;
    }

    public Optional<BrowserSession> find(String sessionId) {
        return Optional.ofNullable(byId.get(sessionId));
    }

    public Optional<BrowserSession> findBySubject(String subject) {
        String id = subjectToSession.get(subject);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public void heartbeat(String sessionId) {
        BrowserSession s = byId.get(sessionId);
        if (s != null) {
            s.setLastHeartbeatAt(clock.instant());
        }
    }

    public int sizeForSubject(String subject) {
        return subjectToSession.containsKey(subject) ? 1 : 0;
    }

    public int size() { return byId.size(); }

    /**
     * Read-only snapshot of live sessions for debugging / observability.
     * Returned views intentionally omit the underlying WebSocket reference
     * (no leaking to controllers).
     */
    public java.util.List<BrowserSessionView> snapshot() {
        var out = new java.util.ArrayList<BrowserSessionView>(byId.size());
        for (var s : byId.values()) {
            out.add(new BrowserSessionView(
                    s.getId(), s.getSubject(), s.getAgentVersion(), s.getLastHeartbeatAt()));
        }
        return out;
    }

    /**
     * Remove sessions whose lastHeartbeatAt is older than STALE_GRACE.
     * Returns the number of sessions reaped.
     */
    public int reapStale() {
        var threshold = clock.instant().minus(STALE_GRACE);
        int reaped = 0;
        for (var entry : byId.entrySet()) {
            BrowserSession s = entry.getValue();
            if (s.getLastHeartbeatAt().isBefore(threshold)) {
                byId.remove(entry.getKey());
                subjectToSession.remove(s.getSubject(), entry.getKey());
                closeQuietly(s.getWs(), new CloseStatus(4408, "heartbeat-timeout"));
                reaped++;
                log.info("[edge] reaped stale session {} (subject={})", s.getId(), s.getSubject());
            }
        }
        return reaped;
    }

    /** Called by the WebSocket handler on close. */
    public void removeByWs(String wsId) {
        for (var entry : byId.entrySet()) {
            if (entry.getValue().getWs().getId().equals(wsId)) {
                byId.remove(entry.getKey());
                subjectToSession.remove(entry.getValue().getSubject(), entry.getKey());
                return;
            }
        }
    }

    private void closeQuietly(WebSocketSession ws, CloseStatus status) {
        try {
            if (ws.isOpen()) ws.close(status);
        } catch (IOException e) {
            log.debug("[edge] suppressed close error: {}", e.getMessage());
        }
    }
}
```

**Step 4b: Add the read-only view DTO `BrowserSessionView.java`**

```java
package vip.mate.browser.edge.session;

import java.time.Instant;

/** Read-only projection of a session — no WebSocket reference. Used by D3. */
public record BrowserSessionView(
        String sessionId,
        String subject,
        String agentVersion,
        Instant lastHeartbeatAt
) {}
```

**Step 5: Run tests, confirm pass, commit**

Run: `cd mateclaw-server && mvn test -Dtest=BrowserSessionRegistryTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

> **Codex re-audit hint**: the concurrency test is `register_concurrentSameSubject_leavesExactlyOneLiveSession`. It launches 64 threads with a `CountDownLatch(go)` so they all hit `register()` in the same window; asserts `byId.size() == 1` and `sizeForSubject("alice") == 1`. If this test ever passes with a non-atomic `register()`, the test is broken — investigate.

```bash
git add mateclaw-server/src/main/java/vip/mate/browser/edge/session/ \
        mateclaw-server/src/test/java/vip/mate/browser/edge/session/
git commit -m "$(cat <<'EOF'
feat(browser): add in-memory BrowserSessionRegistry

Thread-safe registry keyed by server-issued session_id, with
single-active-session-per-subject enforcement (4409 close on
reconnect) via ConcurrentHashMap.compute() to serialise per
subject. 30s heartbeat grace window via reapStale(). Snapshot
projection (no ws leak) for the debug endpoint.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task A4: Edge authentication interceptor

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/auth/EdgeAuthInterceptor.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/auth/EdgePrincipal.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/auth/EdgeAuthInterceptorTest.java`

The interceptor validates the WebSocket upgrade request's `Authorization` header (JWT or PAT) against the **real** MateClaw auth services:

- `vip.mate.auth.service.AuthService.parseClaims(String)` — returns `io.jsonwebtoken.Claims` (or null on bad/expired token).
- `vip.mate.auth.pat.PersonalAccessTokenService.findActiveByPlaintext(String)` — returns `Optional<PersonalAccessTokenEntity>` (entity carries `userId : Long`, `scopes : String`).

Per Codex audit P1-2 and the [PAT entity's own Javadoc](mateclaw-server/src/main/java/vip/mate/auth/pat/PersonalAccessTokenEntity.java:44), per-scope enforcement (the `browser:edge` scope) is a **future RFC**. Phase 1 accepts any active PAT for this endpoint; the TODO comment in the implementation keeps the gap visible.

On success the interceptor stashes an `EdgePrincipal(subject, scheme)` under attribute key `EDGE_PRINCIPAL`. On failure it returns false → Spring rejects with **HTTP 401** (handshake-time auth failure per the rewritten spec).

**Step 1: Write the failing test**

```java
package vip.mate.browser.edge.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EdgeAuthInterceptorTest {

    private AuthService authService;
    private PersonalAccessTokenService patService;
    private EdgeAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        patService = mock(PersonalAccessTokenService.class);
        interceptor = new EdgeAuthInterceptor(authService, patService);
    }

    @Test
    void beforeHandshake_validJwt_storesEdgePrincipalWithSubject() throws Exception {
        Claims c = new DefaultClaims(Map.of("sub", "alice"));
        when(authService.parseClaims("good-jwt")).thenReturn(c);

        var req = httpReq("Bearer good-jwt");
        var resp = httpResp();
        Map<String, Object> attrs = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);

        assertThat(ok).isTrue();
        EdgePrincipal p = (EdgePrincipal) attrs.get("EDGE_PRINCIPAL");
        assertThat(p.subject()).isEqualTo("alice");
        assertThat(p.scheme()).isEqualTo("jwt");
    }

    @Test
    void beforeHandshake_validPat_storesEdgePrincipalWithUserIdAsString() throws Exception {
        // JWT path miss
        when(authService.parseClaims("mt_pat_xyz")).thenReturn(null);
        // PAT path hit
        PersonalAccessTokenEntity entity = new PersonalAccessTokenEntity();
        entity.setUserId(77L);
        entity.setEnabled(true);
        when(patService.findActiveByPlaintext("mt_pat_xyz")).thenReturn(Optional.of(entity));

        var req = httpReq("Bearer mt_pat_xyz");
        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(req, httpResp(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isTrue();
        EdgePrincipal p = (EdgePrincipal) attrs.get("EDGE_PRINCIPAL");
        assertThat(p.subject()).isEqualTo("77");
        assertThat(p.scheme()).isEqualTo("pat");
    }

    @Test
    void beforeHandshake_missingAuthHeader_returnsHttp401() throws Exception {
        var req = httpReq(null);
        var resp = httpResp();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(ok).isFalse();
        assertThat(((ServletServerHttpResponse) resp).getServletResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void beforeHandshake_badToken_returnsHttp401() throws Exception {
        when(authService.parseClaims("bad-jwt")).thenReturn(null);
        when(patService.findActiveByPlaintext("bad-jwt")).thenReturn(Optional.empty());

        var req = httpReq("Bearer bad-jwt");
        var resp = httpResp();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(ok).isFalse();
        assertThat(((ServletServerHttpResponse) resp).getServletResponse().getStatus()).isEqualTo(401);
    }

    private ServerHttpRequest httpReq(String auth) {
        var http = new MockHttpServletRequest("GET", "/api/v1/browser/edge");
        if (auth != null) http.addHeader(HttpHeaders.AUTHORIZATION, auth);
        return new ServletServerHttpRequest(http);
    }

    private ServerHttpResponse httpResp() {
        return new ServletServerHttpResponse(new MockHttpServletResponse());
    }
}
```

**Step 2: Verify the test fails to compile**

Run: `cd mateclaw-server && mvn test -Dtest=EdgeAuthInterceptorTest`
Expected: `cannot find symbol` for `EdgeAuthInterceptor` / `EdgePrincipal`.

> **Codex re-audit hint**: the test now imports real classes (`AuthService`, `PersonalAccessTokenEntity`, `PersonalAccessTokenService`). If any signature has shifted since this plan was written, this will surface as a compile error in step 2 — fix the signatures, do NOT add wrappers.

**Step 3: Implement `EdgePrincipal.java`**

```java
package vip.mate.browser.edge.auth;

/**
 * Authenticated edge principal.
 *
 * <p>{@code subject} carries either the JWT subject (username) or the PAT
 * owner's {@code userId.toString()} — see {@link EdgeAuthInterceptor} for
 * how each is produced. Long-typed identity is introduced in Phase 4.
 *
 * <p>{@code scheme} is "jwt" or "pat" for observability/audit.
 */
public record EdgePrincipal(String subject, String scheme) {}
```

**Step 4: Implement `EdgeAuthInterceptor.java`**

```java
package vip.mate.browser.edge.auth;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.util.Map;
import java.util.Optional;

/**
 * Validates JWT or PAT on the /api/v1/browser/edge WebSocket handshake.
 * On success stashes {@link EdgePrincipal} under {@code EDGE_PRINCIPAL}.
 *
 * <p>Handshake-time auth failures return HTTP 401 (per edge-protocol spec).
 * Post-upgrade auth loss (token revoked mid-session, WS close 4401) is NOT
 * implemented in Phase 1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_PRINCIPAL = "EDGE_PRINCIPAL";

    private final AuthService authService;
    private final PersonalAccessTokenService patService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String token = auth.substring("Bearer ".length()).trim();

        // JWT first — cheaper (signature verify, no DB hit).
        Claims claims = authService.parseClaims(token);
        if (claims != null && claims.getSubject() != null) {
            attributes.put(ATTR_PRINCIPAL, new EdgePrincipal(claims.getSubject(), "jwt"));
            return true;
        }

        // PAT fallback — DB lookup.
        // TODO(phase-3): once per-scope enforcement RFC lands, also check
        //   entity.getScopes().contains("browser:edge") and return 403 if
        //   the token is active but lacks the scope.
        Optional<PersonalAccessTokenEntity> pat = patService.findActiveByPlaintext(token);
        if (pat.isPresent()) {
            attributes.put(ATTR_PRINCIPAL, new EdgePrincipal(pat.get().getUserId().toString(), "pat"));
            return true;
        }

        log.debug("[edge] rejected handshake — bad/expired token");
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // no-op
    }
}
```

**Step 5: Run tests, commit**

Run: `cd mateclaw-server && mvn test -Dtest=EdgeAuthInterceptorTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

```bash
git add mateclaw-server/src/main/java/vip/mate/browser/edge/auth/ \
        mateclaw-server/src/test/java/vip/mate/browser/edge/auth/
git commit -m "$(cat <<'EOF'
feat(browser): add Edge WebSocket handshake auth interceptor

Validates JWT or PAT on /api/v1/browser/edge upgrade requests,
stashes EdgePrincipal in session attributes. PATs must carry
the browser:edge scope.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task A5: Edge WebSocket handler (hello / heartbeat / ping)

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/EdgeWebSocketHandler.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/EdgeWebSocketHandlerTest.java`

**Step 1: Write the failing test**

```java
package vip.mate.browser.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import vip.mate.browser.edge.auth.EdgeAuthInterceptor;
import vip.mate.browser.edge.auth.EdgePrincipal;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSessionRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EdgeWebSocketHandlerTest {

    private BrowserSessionRegistry registry;
    private EdgeWebSocketHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        registry = new BrowserSessionRegistry();
        mapper = new ObjectMapper();
        handler = new EdgeWebSocketHandler(registry, mapper, "1.4.0");
    }

    @Test
    void hello_returnsHelloAckAndRegistersSession() throws Exception {
        WebSocketSession ws = mockWs("user-1");

        EdgeMessage hello = EdgeMessage.builder()
                .v(1).msgId("m1").kind(EdgeMessageKind.HELLO).ts(0).traceId("t1").sessionId("")
                .payload(Map.of(
                        "agent_version", "0.1.0",
                        "os", "windows",
                        "arch", "amd64",
                        "auth", Map.of("scheme", "jwt", "token", "ignored-by-handler")
                )).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(hello)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());

        EdgeMessage ack = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(ack.getKind()).isEqualTo(EdgeMessageKind.HELLO_ACK);
        assertThat(ack.getInReplyTo()).isEqualTo("m1");
        assertThat((String) ack.getPayload().get("session_id")).startsWith("sess-");
        assertThat(ack.getPayload().get("server_version")).isEqualTo("1.4.0");
        assertThat(ack.getPayload().get("heartbeat_interval_ms")).isEqualTo(10000);

        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void heartbeat_updatesRegistryAndAcks() throws Exception {
        WebSocketSession ws = mockWs("user-1");
        var session = registry.register("user-1", ws, "0.1.0");

        EdgeMessage hb = EdgeMessage.builder()
                .v(1).msgId("m2").kind(EdgeMessageKind.HEARTBEAT)
                .ts(0).traceId("t2").sessionId(session.getId())
                .payload(Map.of()).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(hb)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());
        EdgeMessage ack = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(ack.getKind()).isEqualTo(EdgeMessageKind.HEARTBEAT_ACK);
        assertThat(ack.getInReplyTo()).isEqualTo("m2");
    }

    @Test
    void ping_echoesBack() throws Exception {
        WebSocketSession ws = mockWs("user-1");
        var session = registry.register("user-1", ws, "0.1.0");

        EdgeMessage ping = EdgeMessage.builder()
                .v(1).msgId("m3").kind(EdgeMessageKind.PING)
                .ts(0).traceId("t3").sessionId(session.getId())
                .payload(Map.of("echo", "hello-world")).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(ping)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());
        EdgeMessage pong = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(pong.getKind()).isEqualTo(EdgeMessageKind.PONG);
        assertThat(pong.getPayload().get("echo")).isEqualTo("hello-world");
        assertThat(pong.getPayload()).containsKey("server_ts");
    }

    @Test
    void unknownSessionId_sendsAppInvalidSession() throws Exception {
        WebSocketSession ws = mockWs("user-1");

        EdgeMessage ping = EdgeMessage.builder()
                .v(1).msgId("m4").kind(EdgeMessageKind.PING)
                .ts(0).traceId("t4").sessionId("sess-unknown")
                .payload(Map.of("echo", "x")).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(ping)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());
        EdgeMessage err = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(err.getKind()).isEqualTo(EdgeMessageKind.ERROR);
        assertThat(err.getPayload().get("code")).isEqualTo("app.invalid_session");
    }

    @Test
    void otherWsSendsKnownSessionId_returnsBindingMismatch() throws Exception {
        // alice's session, registered on ws-alice
        WebSocketSession alice = mockWs("alice");
        var aliceSession = registry.register("alice", alice, "0.1.0");

        // bob authenticated, on ws-bob, but sends alice's session_id
        WebSocketSession bob = mockWs("bob");

        EdgeMessage spoofed = EdgeMessage.builder()
                .v(1).msgId("m-spoof").kind(EdgeMessageKind.PING)
                .ts(0).traceId("t-spoof").sessionId(aliceSession.getId())
                .payload(Map.of("echo", "evil")).build();

        handler.handleTextMessage(bob, new TextMessage(mapper.writeValueAsString(spoofed)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(bob).sendMessage(sent.capture());
        EdgeMessage err = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(err.getKind()).isEqualTo(EdgeMessageKind.ERROR);
        assertThat(err.getPayload().get("code")).isEqualTo("app.session_binding_mismatch");

        // alice's heartbeat must not have been updated by bob's spoof
        verifyNoInteractions(alice);   // no message sent to alice
    }

    @Test
    void principalMismatch_returnsBindingMismatch() throws Exception {
        // ws registered as alice, but attributes were tampered to look like bob
        WebSocketSession ws = mockWs("alice");
        var session = registry.register("alice", ws, "0.1.0");

        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(EdgeAuthInterceptor.ATTR_PRINCIPAL, new EdgePrincipal("bob", "jwt"));
        when(ws.getAttributes()).thenReturn(attrs);

        EdgeMessage ping = EdgeMessage.builder()
                .v(1).msgId("m-pp").kind(EdgeMessageKind.PING)
                .ts(0).traceId("t-pp").sessionId(session.getId())
                .payload(Map.of("echo", "x")).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(ping)));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws).sendMessage(sent.capture());
        EdgeMessage err = mapper.readValue(sent.getValue().getPayload(), EdgeMessage.class);
        assertThat(err.getPayload().get("code")).isEqualTo("app.session_binding_mismatch");
    }

    @Test
    void unknownProtocolVersion_closes4400() throws Exception {
        WebSocketSession ws = mockWs("user-1");

        EdgeMessage msg = EdgeMessage.builder()
                .v(999).msgId("m5").kind(EdgeMessageKind.PING).ts(0).traceId("t5").sessionId("")
                .payload(Map.of()).build();

        handler.handleTextMessage(ws, new TextMessage(mapper.writeValueAsString(msg)));

        verify(ws).close(argThat(s -> s.getCode() == 4400));
    }

    private WebSocketSession mockWs(String subject) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("ws-" + subject);
        when(ws.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(EdgeAuthInterceptor.ATTR_PRINCIPAL, new EdgePrincipal(subject, "jwt"));
        when(ws.getAttributes()).thenReturn(attrs);
        return ws;
    }
}
```

**Step 2: Run, see it fail**

Run: `cd mateclaw-server && mvn test -Dtest=EdgeWebSocketHandlerTest`
Expected: compile error — handler does not exist.

**Step 3: Implement `EdgeWebSocketHandler.java`**

```java
package vip.mate.browser.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import vip.mate.browser.edge.auth.EdgeAuthInterceptor;
import vip.mate.browser.edge.auth.EdgePrincipal;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 1 Edge WebSocket handler.
 *
 * <p>Handles only protocol envelope kinds defined in edge-protocol.md §1:
 * hello, heartbeat, ping (and the corresponding acks/responses).
 *
 * <p>Unknown kinds are logged and silently dropped per the spec
 * (forward-compat). The only conditions that close the connection are
 * protocol-version mismatch (4400) and an unknown session_id on a
 * post-hello message (4404 inline error, no close).
 */
@Slf4j
@Component
public class EdgeWebSocketHandler extends TextWebSocketHandler {

    private static final int SUPPORTED_PROTOCOL_VERSION = 1;
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private final BrowserSessionRegistry registry;
    private final ObjectMapper mapper;
    private final String serverVersion;

    public EdgeWebSocketHandler(BrowserSessionRegistry registry,
                                ObjectMapper mapper,
                                @Value("${spring.application.version:${revision:dev}}") String serverVersion) {
        this.registry = registry;
        this.mapper = mapper;
        this.serverVersion = serverVersion;
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage payload) throws Exception {
        EdgeMessage msg;
        try {
            msg = mapper.readValue(payload.getPayload(), EdgeMessage.class);
        } catch (Exception e) {
            log.warn("[edge] unparseable frame from ws={}: {}", ws.getId(), e.getMessage());
            ws.close(new CloseStatus(4400, "bad-envelope"));
            return;
        }

        if (msg.getV() != SUPPORTED_PROTOCOL_VERSION) {
            log.warn("[edge] unknown protocol v={} from ws={}", msg.getV(), ws.getId());
            ws.close(new CloseStatus(4400, "unknown-protocol-version"));
            return;
        }

        switch (msg.getKind()) {
            case HELLO -> onHello(ws, msg);
            case HEARTBEAT -> onHeartbeat(ws, msg);
            case PING -> onPing(ws, msg);
            case UNKNOWN -> log.warn("[edge] dropping unknown kind from ws={}", ws.getId());
            default -> log.warn("[edge] kind {} not handled in phase 1", msg.getKind());
        }
    }

    private void onHello(WebSocketSession ws, EdgeMessage hello) throws Exception {
        EdgePrincipal principal = (EdgePrincipal) ws.getAttributes().get(EdgeAuthInterceptor.ATTR_PRINCIPAL);
        if (principal == null) {
            ws.close(new CloseStatus(4401, "no-principal"));
            return;
        }
        String agentVersion = (String) hello.getPayload().getOrDefault("agent_version", "unknown");
        BrowserSession session = registry.register(principal.subject(), ws, agentVersion);

        EdgeMessage ack = reply(hello, EdgeMessageKind.HELLO_ACK, Map.of(
                "session_id", session.getId(),
                "server_version", serverVersion,
                "heartbeat_interval_ms", (int) HEARTBEAT_INTERVAL_MS
        ));
        send(ws, ack);
    }

    private void onHeartbeat(WebSocketSession ws, EdgeMessage hb) throws Exception {
        if (!validSession(ws, hb)) return;
        registry.heartbeat(hb.getSessionId());
        send(ws, reply(hb, EdgeMessageKind.HEARTBEAT_ACK, Map.of()));
    }

    private void onPing(WebSocketSession ws, EdgeMessage ping) throws Exception {
        if (!validSession(ws, ping)) return;
        Object echo = ping.getPayload().getOrDefault("echo", "");
        send(ws, reply(ping, EdgeMessageKind.PONG, Map.of(
                "echo", echo,
                "server_ts", Instant.now().toEpochMilli()
        )));
    }

    /**
     * Three-way binding check (Codex P1-4 fix):
     *   (a) session_id exists in registry;
     *   (b) the session's underlying ws is the *current* socket;
     *   (c) the session's subject matches the authenticated principal.
     * Failure of (a) returns app.invalid_session; (b) or (c) returns
     * app.session_binding_mismatch and is logged at WARN for abuse audit.
     */
    private boolean validSession(WebSocketSession ws, EdgeMessage msg) throws Exception {
        BrowserSession session = registry.find(msg.getSessionId()).orElse(null);
        if (session == null) {
            send(ws, reply(msg, EdgeMessageKind.ERROR, Map.of(
                    "code", "app.invalid_session",
                    "message", "session_id not known to server",
                    "retryable", false
            )));
            return false;
        }
        EdgePrincipal principal = (EdgePrincipal) ws.getAttributes()
                .get(EdgeAuthInterceptor.ATTR_PRINCIPAL);
        boolean wsBindingOk = session.getWs().getId().equals(ws.getId());
        boolean principalOk = principal != null
                && session.getSubject().equals(principal.subject());
        if (!wsBindingOk || !principalOk) {
            log.warn("[edge] session binding mismatch: sessionId={} ws-ok={} principal-ok={}",
                    msg.getSessionId(), wsBindingOk, principalOk);
            send(ws, reply(msg, EdgeMessageKind.ERROR, Map.of(
                    "code", "app.session_binding_mismatch",
                    "message", "session_id is not owned by this connection",
                    "retryable", false
            )));
            return false;
        }
        return true;
    }

    private EdgeMessage reply(EdgeMessage from, EdgeMessageKind kind, Map<String, Object> payload) {
        return EdgeMessage.builder()
                .v(1)
                .msgId(UUID.randomUUID().toString())
                .kind(kind)
                .ts(Instant.now().toEpochMilli())
                .traceId(from.getTraceId())
                .sessionId(from.getSessionId())
                .inReplyTo(from.getMsgId())
                .payload(payload)
                .build();
    }

    private void send(WebSocketSession ws, EdgeMessage msg) throws Exception {
        ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        registry.removeByWs(ws.getId());
        log.info("[edge] ws {} closed: {}", ws.getId(), status);
    }
}
```

**Step 4: Run tests, confirm pass**

Run: `cd mateclaw-server && mvn test -Dtest=EdgeWebSocketHandlerTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0` (5 original + 2 binding tests added by P1-4)

**Step 5: Commit**

```bash
git add mateclaw-server/src/main/java/vip/mate/browser/edge/EdgeWebSocketHandler.java \
        mateclaw-server/src/test/java/vip/mate/browser/edge/EdgeWebSocketHandlerTest.java
git commit -m "$(cat <<'EOF'
feat(browser): add Edge WebSocket handler with hello/heartbeat/ping

Handles the Phase-1 subset of the Edge protocol. Closes the
connection with 4400 on protocol-version mismatch. Returns
app.invalid_session for unknown session ids and
app.session_binding_mismatch when the calling socket or
principal does not own the claimed session_id.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task A6: Wire the Edge endpoint into WebSocketConfig

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/config/WebSocketConfig.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/EdgeEndpointSmokeTest.java` (Spring Boot integration test)

**Step 1: Write a Spring Boot integration test that opens a WS client to the endpoint and expects 401 with no auth header**

```java
package vip.mate.browser.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EdgeEndpointSmokeTest {

    @LocalServerPort int port;
    @Autowired StandardWebSocketClient client;

    @Test
    void noAuthHeader_handshakeReturns401() {
        var uri = URI.create("ws://localhost:" + port + "/api/v1/browser/edge");
        var future = client.execute(new TextWebSocketHandler(){}, null, uri);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("401");
    }
}
```

> **Codex note**: depending on the Spring AI 1.1.x / Spring Boot 3.5 stack, you may need to add `@Import` of a minimal test config to avoid loading every MateClaw bean. Use the smallest slice possible. Also: the `StandardWebSocketClient` bean is not auto-configured for tests by default — add `@Bean StandardWebSocketClient` in a test config if needed. **Verify locally and adjust before pasting blindly.**

**Step 2: Run it, see it fail (endpoint not registered)**

Run: `cd mateclaw-server && mvn test -Dtest=EdgeEndpointSmokeTest`
Expected: failure — connection succeeds (no 401), because the endpoint isn't registered yet.

**Step 3: Modify `WebSocketConfig.java` to register the new handler**

In [mateclaw-server/src/main/java/vip/mate/config/WebSocketConfig.java](mateclaw-server/src/main/java/vip/mate/config/WebSocketConfig.java), inject the new components and add a second registration:

```java
// existing imports...
import vip.mate.browser.edge.EdgeWebSocketHandler;
import vip.mate.browser.edge.auth.EdgeAuthInterceptor;

// in the class, add fields:
private final EdgeWebSocketHandler edgeHandler;
private final EdgeAuthInterceptor edgeAuthInterceptor;

// in registerWebSocketHandlers, append:
@Override
public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(talkModeHandler, "/api/v1/talk/ws")
            .setAllowedOrigins("*");

    registry.addHandler(edgeHandler, "/api/v1/browser/edge")
            .addInterceptors(edgeAuthInterceptor)
            // Edge connections come from Native Host (server-to-server), not
            // browsers — allowed origins is irrelevant; we authenticate via JWT/PAT
            // on the handshake. Leaving the whitelist tight prevents the
            // endpoint being abused as a browser WS surface.
            .setAllowedOrigins("");
}
```

**Step 4: Re-run the integration test**

Run: `cd mateclaw-server && mvn test -Dtest=EdgeEndpointSmokeTest`
Expected: `Tests run: 1, Failures: 0`

**Step 5: Commit**

```bash
git add mateclaw-server/src/main/java/vip/mate/config/WebSocketConfig.java \
        mateclaw-server/src/test/java/vip/mate/browser/edge/EdgeEndpointSmokeTest.java
git commit -m "$(cat <<'EOF'
feat(browser): register /api/v1/browser/edge WebSocket endpoint

Wires EdgeWebSocketHandler and EdgeAuthInterceptor into the
shared WebSocketConfig. AllowedOrigins is intentionally empty —
Native Host is a server-to-server client, not a browser.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task A7: Heartbeat reaper scheduled task

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/session/SessionReaperJob.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/session/SessionReaperJobTest.java`

**Step 1: Write the failing test**

```java
package vip.mate.browser.edge.session;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionReaperJobTest {

    @Test
    void run_invokesRegistryReap() {
        BrowserSessionRegistry registry = mock(BrowserSessionRegistry.class);
        when(registry.reapStale()).thenReturn(3);

        SessionReaperJob job = new SessionReaperJob(registry);
        job.run();

        verify(registry).reapStale();
    }
}
```

**Step 2: Run, see compile failure**

Run: `cd mateclaw-server && mvn test -Dtest=SessionReaperJobTest`
Expected: cannot find symbol.

**Step 3: Implement `SessionReaperJob.java`**

```java
package vip.mate.browser.edge.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically reaps stale Browser Agent sessions whose Native Host has
 * stopped heart-beating. Spring's {@code @Scheduled} is already enabled by
 * MateClawApplication ({@code @EnableScheduling}).
 *
 * <p>Runs every 10 s — same cadence as the heartbeat itself; combined with
 * the 30s grace window in {@link BrowserSessionRegistry#STALE_GRACE} this
 * gives at most 40s between actual silence and removal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionReaperJob {

    private final BrowserSessionRegistry registry;

    @Scheduled(fixedDelay = 10_000)
    public void run() {
        int reaped = registry.reapStale();
        if (reaped > 0) {
            log.info("[edge-reaper] reaped {} stale sessions", reaped);
        }
    }
}
```

**Step 4: Run, confirm pass**

Run: `cd mateclaw-server && mvn test -Dtest=SessionReaperJobTest`
Expected: pass.

**Step 5: Commit**

```bash
git add mateclaw-server/src/main/java/vip/mate/browser/edge/session/SessionReaperJob.java \
        mateclaw-server/src/test/java/vip/mate/browser/edge/session/SessionReaperJobTest.java
git commit -m "$(cat <<'EOF'
feat(browser): add SessionReaperJob to evict stale edge sessions

Runs every 10s, delegates to BrowserSessionRegistry.reapStale().
Combined with the 30s heartbeat grace, removes silent sessions
within ~40s.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task B1: Native Host project scaffolding

**Files:**
- Create: `mateclaw-browser-bridge/go.mod`
- Create: `mateclaw-browser-bridge/cmd/bridge/main.go`
- Create: `mateclaw-browser-bridge/Makefile`
- Create: `mateclaw-browser-bridge/README.md`
- Create: `mateclaw-browser-bridge/.gitignore`

**Step 1: Initialise the Go module**

```bash
mkdir -p mateclaw-browser-bridge/cmd/bridge
cd mateclaw-browser-bridge
go mod init vip.mate/browser-bridge
go mod edit -go=1.22
```

**Step 2: Write the placeholder main**

`mateclaw-browser-bridge/cmd/bridge/main.go`:

```go
// Command bridge is the MateClaw Browser Agent Native Host.
//
// It runs on the user's machine, bridges Chrome Extension (via Native
// Messaging over stdio) to the MateClaw Control Plane (via Bearer-over-WSS).
// See ../docs/specs/edge-protocol.md for the wire format.
package main

import (
	"flag"
	"fmt"
	"os"
)

// Version is injected at build time via -ldflags "-X main.Version=...".
var Version = "dev"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "bridge:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	fs := flag.NewFlagSet("bridge", flag.ContinueOnError)
	versionFlag := fs.Bool("version", false, "print version and exit")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *versionFlag {
		fmt.Println("mateclaw-browser-bridge", Version)
		return nil
	}
	// Phase 1: subcommand wiring lands in Task B6.
	return fmt.Errorf("no command (try --version)")
}
```

**Step 3: Add a Makefile**

`mateclaw-browser-bridge/Makefile`:

```makefile
# MateClaw Browser Bridge - Native Host build & test targets.

VERSION ?= dev
LDFLAGS := -X main.Version=$(VERSION)

.PHONY: build test fmt vet run clean

build:
	go build -ldflags "$(LDFLAGS)" -o bin/bridge ./cmd/bridge

test:
	go test ./...

vet:
	go vet ./...

fmt:
	gofmt -s -w .

clean:
	rm -rf bin/

# Cross-compile for the three OSes the desktop app targets.
build-windows:
	GOOS=windows GOARCH=amd64 go build -ldflags "$(LDFLAGS)" -o bin/bridge-windows-amd64.exe ./cmd/bridge

build-mac:
	GOOS=darwin GOARCH=arm64 go build -ldflags "$(LDFLAGS)" -o bin/bridge-darwin-arm64 ./cmd/bridge
	GOOS=darwin GOARCH=amd64 go build -ldflags "$(LDFLAGS)" -o bin/bridge-darwin-amd64 ./cmd/bridge

build-linux:
	GOOS=linux GOARCH=amd64 go build -ldflags "$(LDFLAGS)" -o bin/bridge-linux-amd64 ./cmd/bridge
```

**Step 4: Add `.gitignore` and `README.md`**

`mateclaw-browser-bridge/.gitignore`:

```
bin/
*.test
*.out
coverage.html
```

`mateclaw-browser-bridge/README.md`:

```markdown
# MateClaw Browser Bridge (Native Host)

Per-user-machine daemon that bridges the MateClaw Chrome Extension
(over Chrome Native Messaging / stdio) to the MateClaw Control Plane
(over Bearer-authenticated WSS).

Phase 1 scope: hello / heartbeat / ping pipeline.

## Build

    make build           # current platform
    make build-windows   # cross-compile
    make test            # run unit tests

## Run (dev)

    ./bin/bridge --version
```

**Step 5: Verify build, commit**

```bash
cd mateclaw-browser-bridge && make build && ./bin/bridge --version
```
Expected: `mateclaw-browser-bridge dev`

```bash
cd .. # back to repo root
git add mateclaw-browser-bridge/go.mod mateclaw-browser-bridge/cmd/bridge/main.go \
        mateclaw-browser-bridge/Makefile mateclaw-browser-bridge/.gitignore \
        mateclaw-browser-bridge/README.md
git commit -m "$(cat <<'EOF'
chore(browser-bridge): scaffold Go native host project

Empty entry point with --version flag and cross-compile targets
for Windows/macOS/Linux. Subcommand wiring follows in B6.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task B2: Configuration loader

**Files:**
- Create: `mateclaw-browser-bridge/internal/config/config.go`
- Test: `mateclaw-browser-bridge/internal/config/config_test.go`

Config sources: env vars first, then a YAML file at `~/.mateclaw/bridge.yaml`, then defaults.

**Step 1: Write the failing test**

```go
package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("MATECLAW_HOME", t.TempDir())
	t.Setenv("MATECLAW_BRIDGE_CP_URL", "")
	t.Setenv("MATECLAW_BRIDGE_AUTH_TOKEN", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ControlPlaneURL != "wss://localhost:18088/api/v1/browser/edge" {
		t.Errorf("default CP URL wrong: %q", cfg.ControlPlaneURL)
	}
	if cfg.HeartbeatInterval.Seconds() != 10 {
		t.Errorf("default heartbeat wrong: %v", cfg.HeartbeatInterval)
	}
}

func TestLoadFromEnv(t *testing.T) {
	t.Setenv("MATECLAW_HOME", t.TempDir())
	t.Setenv("MATECLAW_BRIDGE_CP_URL", "wss://server.example.com/edge")
	t.Setenv("MATECLAW_BRIDGE_AUTH_TOKEN", "tok-123")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ControlPlaneURL != "wss://server.example.com/edge" {
		t.Errorf("env CP URL not applied")
	}
	if cfg.AuthToken != "tok-123" {
		t.Errorf("env token not applied")
	}
}

func TestLoadFromYAML(t *testing.T) {
	home := t.TempDir()
	t.Setenv("MATECLAW_HOME", home)
	t.Setenv("MATECLAW_BRIDGE_CP_URL", "")
	t.Setenv("MATECLAW_BRIDGE_AUTH_TOKEN", "")

	yaml := []byte("control_plane_url: wss://yaml.example.com/edge\nauth_token: yaml-tok\n")
	if err := os.WriteFile(filepath.Join(home, "bridge.yaml"), yaml, 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ControlPlaneURL != "wss://yaml.example.com/edge" {
		t.Errorf("yaml CP URL not applied")
	}
}

func TestEnvBeatsYAML(t *testing.T) {
	home := t.TempDir()
	t.Setenv("MATECLAW_HOME", home)
	t.Setenv("MATECLAW_BRIDGE_CP_URL", "wss://env.example.com/edge")
	t.Setenv("MATECLAW_BRIDGE_AUTH_TOKEN", "env-tok")

	yaml := []byte("control_plane_url: wss://yaml.example.com/edge\n")
	_ = os.WriteFile(filepath.Join(home, "bridge.yaml"), yaml, 0o600)

	cfg, _ := Load()
	if cfg.ControlPlaneURL != "wss://env.example.com/edge" {
		t.Errorf("env should beat yaml")
	}
}
```

**Step 2: Run, expect compile failure**

```bash
cd mateclaw-browser-bridge && go test ./internal/config/...
```
Expected: `package config is not in std`.

**Step 3: Add the YAML dep, then implement `config.go`**

```bash
go get gopkg.in/yaml.v3
```

`internal/config/config.go`:

```go
// Package config loads MateClaw Browser Bridge configuration from
// env vars (highest priority), a YAML file under MATECLAW_HOME (or
// $HOME/.mateclaw), then built-in defaults.
package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	ControlPlaneURL   string        `yaml:"control_plane_url"`
	AuthToken         string        `yaml:"auth_token"`
	AgentVersion      string        `yaml:"agent_version"`
	HeartbeatInterval time.Duration `yaml:"heartbeat_interval"`
	StateDir          string        `yaml:"state_dir"`
}

// Load resolves config in priority order: env vars > YAML file > defaults.
func Load() (*Config, error) {
	cfg := &Config{
		ControlPlaneURL:   "wss://localhost:18088/api/v1/browser/edge",
		HeartbeatInterval: 10 * time.Second,
		AgentVersion:      "dev",
	}

	home := resolveHome()
	cfg.StateDir = filepath.Join(home, "state")

	// Layer 2: YAML
	if y, err := readYAML(filepath.Join(home, "bridge.yaml")); err != nil {
		return nil, err
	} else if y != nil {
		mergeYAML(cfg, y)
	}

	// Layer 1: env (highest priority — overlays last)
	if v := os.Getenv("MATECLAW_BRIDGE_CP_URL"); v != "" {
		cfg.ControlPlaneURL = v
	}
	if v := os.Getenv("MATECLAW_BRIDGE_AUTH_TOKEN"); v != "" {
		cfg.AuthToken = v
	}
	if v := os.Getenv("MATECLAW_BRIDGE_AGENT_VERSION"); v != "" {
		cfg.AgentVersion = v
	}

	return cfg, nil
}

func resolveHome() string {
	if v := os.Getenv("MATECLAW_HOME"); v != "" {
		return v
	}
	h, _ := os.UserHomeDir()
	return filepath.Join(h, ".mateclaw")
}

func readYAML(path string) (*Config, error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", path, err)
	}
	var c Config
	if err := yaml.Unmarshal(b, &c); err != nil {
		return nil, fmt.Errorf("parse %s: %w", path, err)
	}
	return &c, nil
}

func mergeYAML(into, from *Config) {
	if from.ControlPlaneURL != "" {
		into.ControlPlaneURL = from.ControlPlaneURL
	}
	if from.AuthToken != "" {
		into.AuthToken = from.AuthToken
	}
	if from.AgentVersion != "" {
		into.AgentVersion = from.AgentVersion
	}
	if from.HeartbeatInterval > 0 {
		into.HeartbeatInterval = from.HeartbeatInterval
	}
	if from.StateDir != "" {
		into.StateDir = from.StateDir
	}
}
```

**Step 4: Run, confirm pass**

```bash
cd mateclaw-browser-bridge && go test ./internal/config/...
```
Expected: `ok vip.mate/browser-bridge/internal/config`

**Step 5: Commit**

```bash
git add mateclaw-browser-bridge/go.mod mateclaw-browser-bridge/go.sum \
        mateclaw-browser-bridge/internal/config/
git commit -m "$(cat <<'EOF'
feat(browser-bridge): add config loader (env > yaml > defaults)

Resolves MATECLAW_HOME/bridge.yaml then overlays MATECLAW_BRIDGE_*
env vars. Defaults to wss://localhost:18088 and 10s heartbeat.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task B3: EdgeMessage Go types

**Files:**
- Create: `mateclaw-browser-bridge/internal/edgeproto/edgeproto.go`
- Test: `mateclaw-browser-bridge/internal/edgeproto/edgeproto_test.go`

The Go side must serialise bytes that the Java side will deserialise into the *same* `EdgeMessage`. The test below pins this with a golden JSON.

**Step 1: Write the failing test**

```go
package edgeproto

import (
	"encoding/json"
	"testing"
)

func TestMessage_RoundTrip(t *testing.T) {
	m := Message{
		V:         1,
		MsgID:     "00000000-0000-0000-0000-000000000001",
		Kind:      KindPing,
		Ts:        1730000000123,
		TraceID:   "00000000-0000-0000-0000-000000000002",
		SessionID: "sess-abc",
		Payload:   map[string]any{"echo": "hello"},
	}
	b, err := json.Marshal(&m)
	if err != nil {
		t.Fatal(err)
	}

	var back Message
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	if back.Kind != KindPing {
		t.Errorf("kind round-trip lost")
	}
	if back.Payload["echo"] != "hello" {
		t.Errorf("payload round-trip lost: %v", back.Payload)
	}
}

func TestKind_WireFormat(t *testing.T) {
	cases := map[Kind]string{
		KindHello:        "hello",
		KindHelloAck:     "hello.ack",
		KindHeartbeat:    "heartbeat",
		KindHeartbeatAck: "heartbeat.ack",
		KindPing:         "ping",
		KindPong:         "pong",
		KindError:        "error",
	}
	for k, want := range cases {
		got, _ := json.Marshal(k)
		if string(got) != "\""+want+"\"" {
			t.Errorf("kind %d: got %s, want %q", k, got, want)
		}
	}
}

func TestKind_UnknownDecodesToUnknown(t *testing.T) {
	var k Kind
	if err := json.Unmarshal([]byte("\"future.thing\""), &k); err != nil {
		t.Fatal(err)
	}
	if k != KindUnknown {
		t.Errorf("unknown wire kind should decode to KindUnknown, got %v", k)
	}
}
```

**Step 2: Run, expect failure**

```bash
cd mateclaw-browser-bridge && go test ./internal/edgeproto/...
```
Expected: compile error — package does not exist.

**Step 3: Implement `edgeproto.go`**

```go
// Package edgeproto defines the on-wire MateClaw Edge protocol envelope and
// kinds. Mirrors vip.mate.browser.edge.protocol on the Java side. See
// docs/specs/edge-protocol.md for the canonical spec.
package edgeproto

import "encoding/json"

type Kind int

const (
	KindUnknown Kind = iota
	KindHello
	KindHelloAck
	KindHeartbeat
	KindHeartbeatAck
	KindPing
	KindPong
	KindError
)

var kindWire = map[Kind]string{
	KindHello:        "hello",
	KindHelloAck:     "hello.ack",
	KindHeartbeat:    "heartbeat",
	KindHeartbeatAck: "heartbeat.ack",
	KindPing:         "ping",
	KindPong:         "pong",
	KindError:        "error",
}

func (k Kind) MarshalJSON() ([]byte, error) {
	if s, ok := kindWire[k]; ok {
		return json.Marshal(s)
	}
	return json.Marshal("__unknown__")
}

func (k *Kind) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return err
	}
	for kk, ss := range kindWire {
		if ss == s {
			*k = kk
			return nil
		}
	}
	*k = KindUnknown
	return nil
}

// Message is the canonical envelope. All fields are JSON-tagged to match
// docs/specs/edge-protocol.md exactly.
type Message struct {
	V         int            `json:"v"`
	MsgID     string         `json:"msg_id"`
	Kind      Kind           `json:"kind"`
	Ts        int64          `json:"ts"`
	TraceID   string         `json:"trace_id"`
	SessionID string         `json:"session_id"`
	InReplyTo string         `json:"in_reply_to,omitempty"`
	Payload   map[string]any `json:"payload,omitempty"`
}
```

**Step 4: Run, confirm pass**

```bash
cd mateclaw-browser-bridge && go test ./internal/edgeproto/...
```
Expected: `ok`

**Step 5: Commit**

```bash
git add mateclaw-browser-bridge/internal/edgeproto/
git commit -m "$(cat <<'EOF'
feat(browser-bridge): add edgeproto Message types

Mirrors vip.mate.browser.edge.protocol on the Go side. Unknown
wire kinds decode to KindUnknown for forward-compat.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task B4: WSS client with hello + heartbeat + reconnect

**Files:**
- Create: `mateclaw-browser-bridge/internal/edge/client.go`
- Test: `mateclaw-browser-bridge/internal/edge/client_test.go`

Use a fake WS server (Go `httptest.NewServer` + `nhooyr.io/websocket.Accept`) to exercise the client without a real Control Plane.

**Step 1: Add the WS library**

```bash
cd mateclaw-browser-bridge && go get nhooyr.io/websocket
```

**Step 2: Write the failing test**

```go
package edge

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"
	"nhooyr.io/websocket/wsjson"

	"vip.mate/browser-bridge/internal/edgeproto"
)

func TestClient_HelloHandshake(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth := r.Header.Get("Authorization")
		if !strings.HasPrefix(auth, "Bearer ") {
			http.Error(w, "no auth", http.StatusUnauthorized)
			return
		}
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer c.Close(websocket.StatusInternalError, "")

		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()

		var hello edgeproto.Message
		if err := wsjson.Read(ctx, c, &hello); err != nil {
			t.Errorf("read hello: %v", err)
			return
		}
		if hello.Kind != edgeproto.KindHello {
			t.Errorf("expected hello, got %v", hello.Kind)
			return
		}

		ack := edgeproto.Message{
			V: 1, MsgID: "ack-1", Kind: edgeproto.KindHelloAck,
			InReplyTo: hello.MsgID, SessionID: "sess-server-issued",
			Payload: map[string]any{"session_id": "sess-server-issued",
				"server_version": "1.4.0", "heartbeat_interval_ms": 10000},
		}
		_ = wsjson.Write(ctx, c, &ack)
	}))
	defer server.Close()
	wsURL := "ws" + strings.TrimPrefix(server.URL, "http")

	c := NewClient(Options{URL: wsURL, AuthToken: "tok", AgentVersion: "0.1.0"})
	sess, err := c.Connect(context.Background())
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	if sess != "sess-server-issued" {
		t.Errorf("session id not propagated, got %q", sess)
	}
	_ = c.Close()
}

func TestClient_UnauthorizedReturnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "no", http.StatusUnauthorized)
	}))
	defer server.Close()
	wsURL := "ws" + strings.TrimPrefix(server.URL, "http")

	c := NewClient(Options{URL: wsURL, AuthToken: "bad"})
	_, err := c.Connect(context.Background())
	if err == nil {
		t.Fatal("expected error for 401")
	}
	if !strings.Contains(err.Error(), "401") && !strings.Contains(err.Error(), "Unauthorized") {
		t.Errorf("error should mention 401: %v", err)
	}
}
```

**Step 3: Run, expect compile failure**

```bash
go test ./internal/edge/...
```
Expected: `cannot find symbol NewClient`.

**Step 4: Implement `client.go`**

```go
// Package edge is the MateClaw Edge WebSocket client for the Native Host.
package edge

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/google/uuid"
	"nhooyr.io/websocket"
	"nhooyr.io/websocket/wsjson"

	"vip.mate/browser-bridge/internal/edgeproto"
)

type Options struct {
	URL          string
	AuthToken    string
	AgentVersion string
	DialTimeout  time.Duration
}

type Client struct {
	opt       Options
	conn      *websocket.Conn
	sessionID string
}

func NewClient(opt Options) *Client {
	if opt.DialTimeout == 0 {
		opt.DialTimeout = 10 * time.Second
	}
	return &Client{opt: opt}
}

// Connect performs the WSS handshake (HTTP Authorization: Bearer …),
// sends hello, blocks on hello.ack, and returns the server-issued session id.
func (c *Client) Connect(ctx context.Context) (string, error) {
	if c.opt.URL == "" {
		return "", errors.New("edge: empty URL")
	}
	if c.opt.AuthToken == "" {
		return "", errors.New("edge: empty auth token")
	}
	dialCtx, cancel := context.WithTimeout(ctx, c.opt.DialTimeout)
	defer cancel()

	hdr := http.Header{}
	hdr.Set("Authorization", "Bearer "+c.opt.AuthToken)

	conn, resp, err := websocket.Dial(dialCtx, c.opt.URL, &websocket.DialOptions{
		HTTPHeader: hdr,
	})
	if err != nil {
		if resp != nil {
			return "", fmt.Errorf("edge: dial: HTTP %d %s", resp.StatusCode, resp.Status)
		}
		return "", fmt.Errorf("edge: dial: %w", err)
	}
	c.conn = conn

	hello := edgeproto.Message{
		V: 1, MsgID: uuid.NewString(), Kind: edgeproto.KindHello,
		Ts: time.Now().UnixMilli(),
		TraceID: uuid.NewString(),
		SessionID: "",
		Payload: map[string]any{
			"agent_version": c.opt.AgentVersion,
			"os":            goos(), "arch": goarch(),
			"auth": map[string]any{"scheme": "jwt", "token": c.opt.AuthToken},
		},
	}
	if err := wsjson.Write(dialCtx, conn, &hello); err != nil {
		return "", fmt.Errorf("edge: write hello: %w", err)
	}

	var ack edgeproto.Message
	if err := wsjson.Read(dialCtx, conn, &ack); err != nil {
		return "", fmt.Errorf("edge: read hello.ack: %w", err)
	}
	if ack.Kind != edgeproto.KindHelloAck {
		return "", fmt.Errorf("edge: expected hello.ack, got %v", ack.Kind)
	}
	sid, _ := ack.Payload["session_id"].(string)
	if sid == "" {
		return "", errors.New("edge: hello.ack missing session_id")
	}
	c.sessionID = sid
	return sid, nil
}

func (c *Client) SessionID() string { return c.sessionID }

func (c *Client) Close() error {
	if c.conn != nil {
		return c.conn.Close(websocket.StatusNormalClosure, "client-close")
	}
	return nil
}
```

Add helpers at the bottom of the same file:

```go
import (
	"runtime"
)

func goos() string   { return runtime.GOOS }
func goarch() string { return runtime.GOARCH }
```

> **Codex note**: `go get github.com/google/uuid` is needed too — add to step 1.

Run `go mod tidy` to clean up imports.

**Step 5: Run tests, commit**

```bash
go test ./internal/edge/...
```
Expected: pass.

```bash
git add mateclaw-browser-bridge/go.mod mateclaw-browser-bridge/go.sum \
        mateclaw-browser-bridge/internal/edge/
git commit -m "$(cat <<'EOF'
feat(browser-bridge): add Edge WSS client with hello handshake

Connects to wss://.../api/v1/browser/edge with Bearer auth,
sends hello, blocks on hello.ack, exposes server-issued
session_id. Reconnect/backoff lands in B5.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task B5: Heartbeat monitor — interval override + ack tracking + timeout

**Files:**
- Modify: `mateclaw-browser-bridge/internal/edge/client.go`
- Test: `mateclaw-browser-bridge/internal/edge/heartbeat_test.go`

> **Scope split (Codex P1-5 fix)**: this task only handles *liveness within
> a single connection*. Reconnect with exponential backoff lives in the
> runner layer (Task B7) — keep that concern out of `client.go`.
>
> Within this single connection:
> 1. On `hello.ack`, capture `heartbeat_interval_ms` and override the default.
> 2. Send a heartbeat every interval; track `lastAckAt`.
> 3. If `now - lastAckAt > 3 × interval`, return a typed sentinel
>    `ErrHeartbeatTimeout` from `Run` and close the connection with
>    `StatusGoingAway`. The runner sees the typed error and decides to
>    reconnect.
>
> Inbound message handling moves from a single read loop in `Run` to a
> goroutine that publishes to a buffered `inbound chan edgeproto.Message`
> exposed via `Client.Inbound()`. This is the fix for Codex's secondary
> note in B7 about two readers on the same `*websocket.Conn`.

**Step 1: Write the failing tests**

```go
package edge

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"nhooyr.io/websocket"
	"nhooyr.io/websocket/wsjson"
	"vip.mate/browser-bridge/internal/edgeproto"
)

func TestRun_SendsHeartbeatAndReceivesAck(t *testing.T) {
	var heartbeats int32
	server := newFakeCP(t, func(c *websocket.Conn, ctx context.Context, hello edgeproto.Message) {
		_ = wsjson.Write(ctx, c, &edgeproto.Message{
			V: 1, MsgID: "ack", Kind: edgeproto.KindHelloAck, InReplyTo: hello.MsgID,
			Payload: map[string]any{"session_id": "s", "server_version": "1.0",
				"heartbeat_interval_ms": 100},
		})
		for {
			var msg edgeproto.Message
			if err := wsjson.Read(ctx, c, &msg); err != nil { return }
			if msg.Kind == edgeproto.KindHeartbeat {
				atomic.AddInt32(&heartbeats, 1)
				_ = wsjson.Write(ctx, c, &edgeproto.Message{
					V: 1, MsgID: "hbk", Kind: edgeproto.KindHeartbeatAck, InReplyTo: msg.MsgID,
				})
			}
		}
	})
	defer server.Close()

	c := NewClient(Options{URL: wsURL(server), AuthToken: "t", AgentVersion: "0.1.0"})
	ctx, cancel := context.WithTimeout(context.Background(), 600*time.Millisecond)
	defer cancel()
	if _, err := c.Connect(ctx); err != nil { t.Fatal(err) }
	_ = c.Run(ctx)
	if atomic.LoadInt32(&heartbeats) < 3 {
		t.Errorf("expected ≥3 heartbeats; got %d", heartbeats)
	}
}

func TestRun_AppliesServerIssuedInterval(t *testing.T) {
	var first, second time.Time
	got := make(chan struct{}, 2)
	server := newFakeCP(t, func(c *websocket.Conn, ctx context.Context, hello edgeproto.Message) {
		_ = wsjson.Write(ctx, c, &edgeproto.Message{
			V: 1, MsgID: "ack", Kind: edgeproto.KindHelloAck, InReplyTo: hello.MsgID,
			Payload: map[string]any{"heartbeat_interval_ms": 80, "session_id": "s"},
		})
		for i := 0; ; i++ {
			var msg edgeproto.Message
			if err := wsjson.Read(ctx, c, &msg); err != nil { return }
			if msg.Kind == edgeproto.KindHeartbeat {
				if i == 0 { first = time.Now(); got <- struct{}{} }
				if i == 1 { second = time.Now(); got <- struct{}{} }
				_ = wsjson.Write(ctx, c, &edgeproto.Message{
					V: 1, MsgID: "hbk", Kind: edgeproto.KindHeartbeatAck, InReplyTo: msg.MsgID,
				})
			}
		}
	})
	defer server.Close()

	c := NewClient(Options{URL: wsURL(server), AuthToken: "t"})
	ctx, cancel := context.WithTimeout(context.Background(), 600*time.Millisecond)
	defer cancel()
	if _, err := c.Connect(ctx); err != nil { t.Fatal(err) }
	go c.Run(ctx)

	<-got; <-got
	diff := second.Sub(first)
	if diff < 60*time.Millisecond || diff > 140*time.Millisecond {
		t.Errorf("server-issued 80ms interval not applied (gap=%v)", diff)
	}
}

func TestRun_ReturnsHeartbeatTimeoutWhenServerStopsAcking(t *testing.T) {
	server := newFakeCP(t, func(c *websocket.Conn, ctx context.Context, hello edgeproto.Message) {
		_ = wsjson.Write(ctx, c, &edgeproto.Message{
			V: 1, MsgID: "ack", Kind: edgeproto.KindHelloAck, InReplyTo: hello.MsgID,
			Payload: map[string]any{"heartbeat_interval_ms": 50, "session_id": "s"},
		})
		// Read but never ack.
		for {
			var msg edgeproto.Message
			if err := wsjson.Read(ctx, c, &msg); err != nil { return }
			_ = msg
		}
	})
	defer server.Close()

	c := NewClient(Options{URL: wsURL(server), AuthToken: "t"})
	ctx, cancel := context.WithTimeout(context.Background(), 600*time.Millisecond)
	defer cancel()
	if _, err := c.Connect(ctx); err != nil { t.Fatal(err) }
	err := c.Run(ctx)
	if !errors.Is(err, ErrHeartbeatTimeout) {
		t.Errorf("expected ErrHeartbeatTimeout, got %v", err)
	}
}

// ── helpers ──────────────────────────────────────────────────────────────
func newFakeCP(t *testing.T, after func(c *websocket.Conn, ctx context.Context, hello edgeproto.Message)) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil { t.Errorf("accept: %v", err); return }
		defer c.Close(websocket.StatusInternalError, "")
		ctx := r.Context()
		var hello edgeproto.Message
		if err := wsjson.Read(ctx, c, &hello); err != nil { return }
		after(c, ctx, hello)
	}))
}
func wsURL(s *httptest.Server) string { return "ws" + strings.TrimPrefix(s.URL, "http") }
```

**Step 2: Run, see compile failures**

```bash
go test ./internal/edge/...
```
Expected: `ErrHeartbeatTimeout undefined`, `Client.Run undefined`, etc.

**Step 3: Implement `client.go` extensions**

```go
// ─── Heartbeat monitor (B5) ─────────────────────────────────────────────

// ErrHeartbeatTimeout is returned by Run when the server has not acked a
// heartbeat within 3 × the heartbeat interval. The runner converts this
// into a reconnect (B7).
var ErrHeartbeatTimeout = errors.New("edge: heartbeat ack timeout")

// Options gains nothing — interval is owned by the server via hello.ack.
// We keep a fallback default in the Client itself.
const defaultHeartbeatInterval = 10 * time.Second

// Client gains 3 fields:
type Client struct {
	opt          Options
	conn         *websocket.Conn
	sessionID    string
	interval     time.Duration   // resolved from hello.ack or default
	lastAckAt    atomic.Int64    // unix-nano of most recent heartbeat.ack
	inbound      chan edgeproto.Message
}

// After Connect parses hello.ack:
//   c.interval = pickInterval(ack.Payload["heartbeat_interval_ms"])
//   c.lastAckAt.Store(time.Now().UnixNano())
//   c.inbound = make(chan edgeproto.Message, 64)
//   go c.readLoop(ctx)         // single owner of wsjson.Read; publishes
//                              // heartbeat.ack to lastAckAt and others to inbound
//
// Inbound() returns the read-only side.
func (c *Client) Inbound() <-chan edgeproto.Message { return c.inbound }

func pickInterval(v any) time.Duration {
	if ms, ok := v.(float64); ok && ms > 0 {
		return time.Duration(ms) * time.Millisecond
	}
	return defaultHeartbeatInterval
}

// Run blocks until ctx is cancelled, the read loop reports an error, or
// the heartbeat watchdog fires.
func (c *Client) Run(ctx context.Context) error {
	if c.conn == nil { return errors.New("edge: Run before Connect") }
	tick := time.NewTicker(c.interval)
	defer tick.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-tick.C:
			// (a) send heartbeat
			if err := c.sendHeartbeat(ctx); err != nil { return err }
			// (b) watchdog
			ageNs := time.Now().UnixNano() - c.lastAckAt.Load()
			if ageNs > int64(3*c.interval) {
				_ = c.conn.Close(websocket.StatusGoingAway, "heartbeat-timeout")
				return ErrHeartbeatTimeout
			}
		}
	}
}

func (c *Client) sendHeartbeat(ctx context.Context) error {
	return wsjson.Write(ctx, c.conn, &edgeproto.Message{
		V: 1, MsgID: uuid.NewString(), Kind: edgeproto.KindHeartbeat,
		Ts: time.Now().UnixMilli(), TraceID: uuid.NewString(), SessionID: c.sessionID,
	})
}

func (c *Client) readLoop(ctx context.Context) {
	defer close(c.inbound)
	for {
		var msg edgeproto.Message
		if err := wsjson.Read(ctx, c.conn, &msg); err != nil {
			return
		}
		if msg.Kind == edgeproto.KindHeartbeatAck {
			c.lastAckAt.Store(time.Now().UnixNano())
			continue
		}
		select {
		case c.inbound <- msg:
		default:
			// Buffer full — drop oldest to preserve liveness.
			select { case <-c.inbound: default: }
			c.inbound <- msg
		}
	}
}
```

> **Codex re-audit hint**: there is now **exactly one** reader on `c.conn`
> (`readLoop`), removing the dual-reader bug Codex flagged in B7. The runner
> consumes from `Client.Inbound()` (a channel), not from the WebSocket directly.

**Step 4: Run, confirm pass**

```bash
go test ./internal/edge/... -count=1 -race
```
Expected: all three new tests pass, `-race` clean.

**Step 5: Commit**

```bash
git add mateclaw-browser-bridge/internal/edge/
git commit -m "$(cat <<'EOF'
feat(browser-bridge): heartbeat ack monitor + interval from hello.ack

Client.Run now sends heartbeats at the server-issued interval,
tracks lastAckAt via an atomic, and returns ErrHeartbeatTimeout
after 3× silence. Reading is consolidated into a single readLoop
that publishes non-ack frames on an Inbound() channel — removes
the dual-reader race the previous draft introduced.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task B6: Native Messaging server (stdio bridge)

**Files:**
- Create: `mateclaw-browser-bridge/internal/nm/server.go`
- Test: `mateclaw-browser-bridge/internal/nm/server_test.go`

Chrome Native Messaging protocol: every message is a uint32 little-endian length prefix followed by UTF-8 JSON. Max 1 MB per message.

**Step 1: Write the failing test**

```go
package nm

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"io"
	"testing"
)

func TestServer_ReadOneFrame(t *testing.T) {
	payload := []byte(`{"hello":"world"}`)
	var hdr [4]byte
	binary.LittleEndian.PutUint32(hdr[:], uint32(len(payload)))

	r := bytes.NewReader(append(hdr[:], payload...))
	got, err := ReadFrame(r)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != `{"hello":"world"}` {
		t.Errorf("payload mismatch: %s", got)
	}
}

func TestServer_WriteOneFrame(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteFrame(&buf, []byte(`{"k":1}`)); err != nil {
		t.Fatal(err)
	}
	got := buf.Bytes()
	if len(got) < 4 {
		t.Fatal("frame too short")
	}
	length := binary.LittleEndian.Uint32(got[:4])
	if int(length) != len(got)-4 {
		t.Errorf("length prefix wrong: got %d, payload %d", length, len(got)-4)
	}
}

func TestServer_RejectsOverlargeFrame(t *testing.T) {
	var hdr [4]byte
	binary.LittleEndian.PutUint32(hdr[:], 2*1024*1024) // 2 MB
	r := bytes.NewReader(append(hdr[:], make([]byte, 16)...))
	_, err := ReadFrame(r)
	if err == nil {
		t.Fatal("expected over-size error")
	}
}

func TestServer_EOFOnClose(t *testing.T) {
	_, err := ReadFrame(bytes.NewReader(nil))
	if err != io.EOF {
		t.Errorf("expected io.EOF on empty stream, got %v", err)
	}
}

func TestServer_JSONFrame(t *testing.T) {
	type x struct{ A int }
	var buf bytes.Buffer
	if err := WriteJSONFrame(&buf, x{A: 7}); err != nil {
		t.Fatal(err)
	}
	frame, _ := ReadFrame(&buf)
	var back x
	_ = json.Unmarshal(frame, &back)
	if back.A != 7 {
		t.Errorf("json round-trip lost: %v", back)
	}
}
```

**Step 2: Run, see compile failure**

```bash
go test ./internal/nm/...
```
Expected: `cannot find symbol ReadFrame`.

**Step 3: Implement `server.go`**

```go
// Package nm implements the Chrome Native Messaging stdio frame protocol:
//   uint32 little-endian length prefix + UTF-8 JSON payload.
//
// Max payload size is 1 MB (Chrome's hard limit). Anything bigger is a
// protocol violation; we return an error instead of allocating.
package nm

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

const MaxFrameBytes = 1024 * 1024 // 1 MB — Chrome's documented hard cap.

// ReadFrame reads one Native Messaging frame from r and returns the raw
// JSON payload (without the length prefix). Returns io.EOF when the peer
// has closed cleanly.
func ReadFrame(r io.Reader) ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		if errors.Is(err, io.ErrUnexpectedEOF) || errors.Is(err, io.EOF) {
			return nil, io.EOF
		}
		return nil, fmt.Errorf("nm: read header: %w", err)
	}
	length := binary.LittleEndian.Uint32(hdr[:])
	if length == 0 {
		return []byte{}, nil
	}
	if int(length) > MaxFrameBytes {
		return nil, fmt.Errorf("nm: frame %d exceeds 1MB cap", length)
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, fmt.Errorf("nm: read payload: %w", err)
	}
	return buf, nil
}

// WriteFrame writes a single Native Messaging frame to w.
func WriteFrame(w io.Writer, payload []byte) error {
	if len(payload) > MaxFrameBytes {
		return fmt.Errorf("nm: refusing to write %d-byte frame (limit %d)", len(payload), MaxFrameBytes)
	}
	var hdr [4]byte
	binary.LittleEndian.PutUint32(hdr[:], uint32(len(payload)))
	if _, err := w.Write(hdr[:]); err != nil {
		return fmt.Errorf("nm: write header: %w", err)
	}
	if _, err := w.Write(payload); err != nil {
		return fmt.Errorf("nm: write payload: %w", err)
	}
	return nil
}

// WriteJSONFrame is a small convenience over WriteFrame.
func WriteJSONFrame(w io.Writer, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return WriteFrame(w, b)
}
```

**Step 4: Run, confirm pass**

```bash
go test ./internal/nm/...
```
Expected: pass.

**Step 5: Commit**

```bash
git add mateclaw-browser-bridge/internal/nm/
git commit -m "$(cat <<'EOF'
feat(browser-bridge): add Chrome Native Messaging frame codec

Implements ReadFrame / WriteFrame / WriteJSONFrame per Chrome's
NM protocol (uint32 LE length + JSON). Rejects frames over 1 MB.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task B7: Runner (stdin NM ↔ Edge WSS) with session_id stamp + reconnect loop

**Files:**
- Modify: `mateclaw-browser-bridge/cmd/bridge/main.go`
- Create: `mateclaw-browser-bridge/internal/runner/runner.go`
- Test: `mateclaw-browser-bridge/internal/runner/runner_test.go`

Runner orchestrates three concerns:

1. **Stdin → Edge**: read NM frames from stdin, **override `SessionID` to the
   current client's session id** (Codex P0-1 — Native Host is sole owner of
   session_id; Extension is not trusted to choose it), forward to Edge.
2. **Edge → Stdout**: consume `Client.Inbound()` channel, encode each as NM
   frame on stdout.
3. **Reconnect loop**: when `Client.Run` returns `ErrHeartbeatTimeout` or any
   IO error, run `Connect → Run` again with exponential backoff (1s, 2s, 4s,
   8s, 16s, 32s, capped at 60s, ±20% jitter). Give up after 5 consecutive
   failures and exit non-zero — Chrome then sees Native Messaging EOF and
   surfaces a disconnect to the Extension (the correct propagation).

**Step 1: Write the failing tests**

```go
package runner

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"nhooyr.io/websocket"
	"nhooyr.io/websocket/wsjson"

	"vip.mate/browser-bridge/internal/edge"
	"vip.mate/browser-bridge/internal/edgeproto"
	"vip.mate/browser-bridge/internal/nm"
)

// ── test 1: ping → pong, session_id is stamped from server ───────────────
func TestRunner_PingPong_SessionIdStampedByBridge(t *testing.T) {
	var sawSessionID string
	server := newFakeEdge(t, "sess-server-1", func(c *websocket.Conn, ctx context.Context, msg edgeproto.Message) {
		if msg.Kind == edgeproto.KindPing {
			sawSessionID = msg.SessionID
			_ = wsjson.Write(ctx, c, &edgeproto.Message{
				V: 1, MsgID: "pong", Kind: edgeproto.KindPong,
				InReplyTo: msg.MsgID, SessionID: msg.SessionID,
				Payload: map[string]any{"echo": msg.Payload["echo"]},
			})
		}
	})
	defer server.Close()

	// Extension-side ping has session_id="" — runner must stamp it.
	pingJSON, _ := json.Marshal(edgeproto.Message{
		V: 1, MsgID: "p1", Kind: edgeproto.KindPing, SessionID: "",
		Payload: map[string]any{"echo": "yo"},
	})
	var stdin bytes.Buffer
	_ = nm.WriteFrame(&stdin, pingJSON)
	var stdout safeBuf

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r := New(Options{
		EdgeClient: edge.NewClient(edge.Options{URL: wsURL(server), AuthToken: "t"}),
		Stdin: &stdin, Stdout: &stdout,
	})
	_ = r.runOnce(ctx) // single-connection variant for the test

	frame, err := nm.ReadFrame(&stdout)
	if err != nil { t.Fatalf("ReadFrame: %v", err) }
	var pong edgeproto.Message
	_ = json.Unmarshal(frame, &pong)
	if pong.Kind != edgeproto.KindPong { t.Errorf("expected pong, got %v", pong.Kind) }
	if pong.Payload["echo"] != "yo" { t.Errorf("echo lost: %v", pong.Payload) }
	if sawSessionID != "sess-server-1" {
		t.Errorf("server saw session_id=%q, expected the server-issued sess-server-1", sawSessionID)
	}
}

// ── test 2: bridge overrides a session_id the extension tried to set ─────
func TestRunner_OverridesAttackerSuppliedSessionId(t *testing.T) {
	var sawSessionID string
	server := newFakeEdge(t, "sess-real", func(c *websocket.Conn, ctx context.Context, msg edgeproto.Message) {
		if msg.Kind == edgeproto.KindPing {
			sawSessionID = msg.SessionID
		}
	})
	defer server.Close()

	pingJSON, _ := json.Marshal(edgeproto.Message{
		V: 1, MsgID: "p1", Kind: edgeproto.KindPing,
		SessionID: "sess-attacker-tries-this", // ← extension tries to spoof
		Payload: map[string]any{"echo": "x"},
	})
	var stdin bytes.Buffer
	_ = nm.WriteFrame(&stdin, pingJSON)
	var stdout safeBuf

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	r := New(Options{
		EdgeClient: edge.NewClient(edge.Options{URL: wsURL(server), AuthToken: "t"}),
		Stdin: &stdin, Stdout: &stdout,
	})
	_ = r.runOnce(ctx)

	if sawSessionID != "sess-real" {
		t.Errorf("bridge did not override spoofed session_id; server saw %q", sawSessionID)
	}
}

// ── test 3: reconnect loop honours backoff and recovers ──────────────────
func TestRunner_ReconnectsWithBackoff(t *testing.T) {
	var dialCount atomic.Int32
	mux := http.NewServeMux()
	mux.HandleFunc("/edge", func(w http.ResponseWriter, r *http.Request) {
		n := dialCount.Add(1)
		c, err := websocket.Accept(w, r, nil)
		if err != nil { return }
		// First two attempts: accept hello then immediately close — runner
		// must reconnect. Third attempt: stay up.
		ctx := r.Context()
		var hello edgeproto.Message
		_ = wsjson.Read(ctx, c, &hello)
		_ = wsjson.Write(ctx, c, &edgeproto.Message{
			V: 1, MsgID: "ack", Kind: edgeproto.KindHelloAck, InReplyTo: hello.MsgID,
			Payload: map[string]any{"session_id": "s", "heartbeat_interval_ms": 50},
		})
		if n < 3 {
			c.Close(websocket.StatusInternalError, "test-flake")
			return
		}
		// Hold the third connection open until ctx done.
		<-ctx.Done()
	})
	server := httptest.NewServer(mux)
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	r := New(Options{
		EdgeClient: edge.NewClient(edge.Options{URL: wsURL(server) + "/edge", AuthToken: "t"}),
		Stdin: bytes.NewReader(nil), Stdout: &safeBuf{},
		// Test seam: faster backoff so the test wraps in seconds.
		BackoffBase: 50 * time.Millisecond, BackoffMax: 500 * time.Millisecond,
		MaxAttempts: 5,
	})
	_ = r.Run(ctx)
	if dialCount.Load() < 3 {
		t.Errorf("expected ≥3 dials, got %d", dialCount.Load())
	}
}

// ── helpers ──────────────────────────────────────────────────────────────
type safeBuf struct { mu sync.Mutex; buf bytes.Buffer }
func (s *safeBuf) Write(p []byte) (int, error) { s.mu.Lock(); defer s.mu.Unlock(); return s.buf.Write(p) }
func (s *safeBuf) Read(p []byte) (int, error)  { s.mu.Lock(); defer s.mu.Unlock(); return s.buf.Read(p) }

func newFakeEdge(t *testing.T, sessionID string, onMsg func(c *websocket.Conn, ctx context.Context, msg edgeproto.Message)) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil { return }
		defer c.Close(websocket.StatusInternalError, "")
		ctx := r.Context()
		var hello edgeproto.Message
		if err := wsjson.Read(ctx, c, &hello); err != nil { return }
		_ = wsjson.Write(ctx, c, &edgeproto.Message{
			V: 1, MsgID: "ack", Kind: edgeproto.KindHelloAck, InReplyTo: hello.MsgID,
			Payload: map[string]any{"session_id": sessionID, "heartbeat_interval_ms": 50},
		})
		for {
			var msg edgeproto.Message
			if err := wsjson.Read(ctx, c, &msg); err != nil { return }
			onMsg(c, ctx, msg)
		}
	}))
}
func wsURL(s *httptest.Server) string { return "ws" + strings.TrimPrefix(s.URL, "http") }
```

**Step 2: Verify the tests fail to compile**

```bash
go test ./internal/runner/...
```
Expected: `Runner / Options / New undefined`.

**Step 3: Implement `runner.go`**

```go
// Package runner orchestrates the Native Host pipeline:
//
//   stdin (NM frame) ── [session_id stamp] ──→ Edge.Conn
//   Edge.Inbound chan ────────────────────────→ stdout (NM frame)
//
// On connection failure or ErrHeartbeatTimeout, reconnects with
// exponential backoff (Options.BackoffBase, doubling, capped at
// Options.BackoffMax, ±20% jitter) up to Options.MaxAttempts times.
package runner

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"math/rand"
	"sync"
	"time"

	"nhooyr.io/websocket/wsjson"

	"vip.mate/browser-bridge/internal/edge"
	"vip.mate/browser-bridge/internal/edgeproto"
	"vip.mate/browser-bridge/internal/nm"
)

type Options struct {
	EdgeClient  *edge.Client
	Stdin       io.Reader
	Stdout      io.Writer
	// Reconnect backoff. Zero values mean defaults (1s base, 60s cap, 5 attempts).
	BackoffBase time.Duration
	BackoffMax  time.Duration
	MaxAttempts int
}

type Runner struct{ opt Options }

func New(opt Options) *Runner {
	if opt.BackoffBase == 0 { opt.BackoffBase = 1 * time.Second }
	if opt.BackoffMax == 0  { opt.BackoffMax  = 60 * time.Second }
	if opt.MaxAttempts == 0 { opt.MaxAttempts = 5 }
	return &Runner{opt: opt}
}

// Run wraps runOnce in a reconnect loop with exponential backoff.
func (r *Runner) Run(ctx context.Context) error {
	if r.opt.EdgeClient == nil { return errors.New("runner: nil EdgeClient") }
	attempt := 0
	wait := r.opt.BackoffBase
	for {
		err := r.runOnce(ctx)
		if ctx.Err() != nil { return ctx.Err() }
		if err == nil { return nil }
		if errors.Is(err, io.EOF) { return nil } // clean shutdown on stdin close

		attempt++
		if attempt >= r.opt.MaxAttempts {
			return err
		}
		sleep := jitter(wait)
		select {
		case <-time.After(sleep):
		case <-ctx.Done():
			return ctx.Err()
		}
		wait *= 2
		if wait > r.opt.BackoffMax { wait = r.opt.BackoffMax }
	}
}

// runOnce: one Connect → Run lifecycle. Returns nil on clean stdin EOF;
// any other error tells Run to consider reconnecting.
func (r *Runner) runOnce(ctx context.Context) error {
	if _, err := r.opt.EdgeClient.Connect(ctx); err != nil { return err }

	var wg sync.WaitGroup
	errCh := make(chan error, 3)

	// Outbound: stdin → edge (with session_id stamp)
	wg.Add(1)
	go func() { defer wg.Done(); errCh <- r.pumpStdinToEdge(ctx) }()

	// Inbound: edge channel → stdout
	wg.Add(1)
	go func() { defer wg.Done(); errCh <- r.pumpInboundToStdout(ctx) }()

	// Heartbeat watchdog (returns ErrHeartbeatTimeout to trigger reconnect)
	wg.Add(1)
	go func() { defer wg.Done(); errCh <- r.opt.EdgeClient.Run(ctx) }()

	go func() { wg.Wait(); close(errCh) }()

	for err := range errCh {
		if err != nil && !errors.Is(err, context.Canceled) {
			return err
		}
	}
	return nil
}

func (r *Runner) pumpStdinToEdge(ctx context.Context) error {
	for {
		if ctx.Err() != nil { return ctx.Err() }
		frame, err := nm.ReadFrame(r.opt.Stdin)
		if err == io.EOF { return io.EOF }
		if err != nil { return err }

		var msg edgeproto.Message
		if err := json.Unmarshal(frame, &msg); err != nil { continue } // drop garbage

		// (Codex P0-1) Native Host is the sole owner of session_id.
		// Always override whatever the extension sent.
		msg.SessionID = r.opt.EdgeClient.SessionID()

		if err := wsjson.Write(ctx, r.opt.EdgeClient.Conn(), &msg); err != nil {
			return err
		}
	}
}

func (r *Runner) pumpInboundToStdout(ctx context.Context) error {
	in := r.opt.EdgeClient.Inbound()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case msg, ok := <-in:
			if !ok { return nil } // client closed
			b, err := json.Marshal(&msg)
			if err != nil { continue }
			if err := nm.WriteFrame(r.opt.Stdout, b); err != nil { return err }
		}
	}
}

func jitter(d time.Duration) time.Duration {
	// ±20% jitter
	delta := float64(d) * 0.2
	return d + time.Duration((rand.Float64()*2-1)*delta)
}
```

Also expose `Client.Conn()` on `edge.Client` (single line):

```go
func (c *Client) Conn() *websocket.Conn { return c.conn }
```

**Step 4: Run, confirm pass**

```bash
go test ./... -count=1 -race
```
Expected: all three runner tests pass, no race warnings.

**Step 5: Wire the `run` subcommand in `main.go`**

```go
// in cmd/bridge/main.go
switch fs.Arg(0) {
case "run":
	return runDaemon(args[1:])
case "":
	return fmt.Errorf("missing subcommand (try 'run' or '--version')")
default:
	return fmt.Errorf("unknown command: %s", fs.Arg(0))
}
```

`runDaemon` wires `config.Load()` → `edge.NewClient` → `runner.New(... Stdin: os.Stdin, Stdout: os.Stdout)` → `r.Run(ctx)`. Use `signal.NotifyContext(ctx, os.Interrupt)` so Ctrl+C cancels cleanly.

```bash
git add mateclaw-browser-bridge/
git commit -m "$(cat <<'EOF'
feat(browser-bridge): runner with session_id stamp + reconnect loop

Bridges stdin NM frames to the Edge WSS connection, overriding
session_id with the server-issued value (Native Host is the
sole owner; extension is not trusted). On ErrHeartbeatTimeout
or IO error, reconnects with exponential backoff (1s→60s, ±20%
jitter, max 5 attempts) before exiting non-zero.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task C1: Extension project scaffolding

**Files:**
- Create: `mateclaw-extension/package.json`
- Create: `mateclaw-extension/tsconfig.json`
- Create: `mateclaw-extension/vite.config.ts`
- Create: `mateclaw-extension/.gitignore`
- Create: `mateclaw-extension/README.md`

**Step 1: Bootstrap with pnpm**

```bash
mkdir mateclaw-extension && cd mateclaw-extension
pnpm init
pnpm add -D vue@^3 typescript@^5 vite@^6 @vitejs/plugin-vue \
            @types/chrome vitest @vue/tsconfig
```

**Step 2: Write `package.json`**

```json
{
  "name": "mateclaw-extension",
  "version": "0.1.0",
  "private": true,
  "description": "MateClaw Browser Agent — Chrome MV3 extension",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc --noEmit && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  }
}
```

**Step 3: `tsconfig.json`**

```json
{
  "extends": "@vue/tsconfig/tsconfig.dom.json",
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "types": ["chrome", "vitest/globals"]
  },
  "include": ["src/**/*.ts", "src/**/*.vue"]
}
```

**Step 4: `vite.config.ts` and `.gitignore`**

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        sidepanel: 'sidepanel.html',
        'service-worker': 'src/sw/index.ts',
        // offscreen.html joins in Phase 2 with chrome.debugger work.
      },
      output: {
        entryFileNames: '[name].js',
        chunkFileNames: 'chunks/[name]-[hash].js',
      },
    },
  },
})
```

`.gitignore`:

```
node_modules/
dist/
*.log
.vscode/
```

**Step 5: Verify `pnpm install` clean, commit**

```bash
pnpm install
git add mateclaw-extension/package.json mateclaw-extension/tsconfig.json \
        mateclaw-extension/vite.config.ts mateclaw-extension/.gitignore \
        mateclaw-extension/README.md mateclaw-extension/pnpm-lock.yaml
git commit -m "$(cat <<'EOF'
chore(extension): scaffold Vue 3 + Vite + TS extension project

Two bundle entrypoints: sidepanel UI and service worker.
Offscreen document scaffolding joins in Phase 2 alongside
chrome.debugger work. Vitest configured for unit tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task C2: MV3 manifest with required permissions

**Files:**
- Create: `mateclaw-extension/manifest.json`
- Create: `mateclaw-extension/sidepanel.html`

**Step 1: Write `manifest.json`**

Permission set borrowed from the Claude Chrome extension reference at `C:\Users\zhou_\Desktop\Claude-Chrome-应用商店\manifest.json`. Phase 1 requests the **minimum** needed for the ping pipeline: `sidePanel`, `storage`, `alarms`, `notifications`, `nativeMessaging`. `debugger` and `offscreen` join in Phase 2 — keeping them out now narrows the install permission prompt and the audit surface.

```json
{
  "manifest_version": 3,
  "name": "MateClaw Browser Agent (dev)",
  "version": "0.1.0",
  "description": "MateClaw Browser Agent — Phase 1 connectivity",
  "minimum_chrome_version": "116",
  "icons": { "128": "icon-128.png" },
  "background": {
    "service_worker": "service-worker.js",
    "type": "module"
  },
  "action": { "default_title": "Open MateClaw" },
  "side_panel": { "default_path": "sidepanel.html" },
  "permissions": [
    "sidePanel",
    "storage",
    "alarms",
    "notifications",
    "nativeMessaging"
  ],
  "host_permissions": ["<all_urls>"],
  "content_security_policy": {
    "extension_pages": "script-src 'self'; object-src 'self'; connect-src 'self' ws://localhost:18088 wss://localhost:18088"
  }
}
```

**Step 2: Empty placeholder HTML**

`sidepanel.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>MateClaw</title></head>
<body><div id="app"></div><script type="module" src="/src/sidepanel/index.ts"></script></body>
</html>
```

**Step 3: Add a 128×128 placeholder icon**

Copy any 128×128 PNG into `mateclaw-extension/icon-128.png`. For the plan we mark "use an internal placeholder; final design follows in Phase 2".

**Step 4: Verify `vite build` runs without entries failing**

```bash
cd mateclaw-extension && pnpm build
```
Expected: builds without errors (HTML and TS entries resolve; placeholder TS files will be added in C3–C5).

**Step 5: Commit**

```bash
git add mateclaw-extension/manifest.json mateclaw-extension/sidepanel.html \
        mateclaw-extension/icon-128.png
git commit -m "$(cat <<'EOF'
feat(extension): add MV3 manifest with phase-1 minimum permissions

sidePanel + nativeMessaging are the only foundation permissions
the ping pipeline needs. Debugger and offscreen are intentionally
deferred to Phase 2 to keep the install audit surface small.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task C3: EdgeMessage TypeScript types

**Files:**
- Create: `mateclaw-extension/src/shared/edge-protocol.ts`
- Test: `mateclaw-extension/src/shared/edge-protocol.test.ts`

**Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { parseEdgeMessage, makeEdgeMessage, EdgeMessageKind } from './edge-protocol'

describe('edge-protocol', () => {
  it('serialises a ping', () => {
    const m = makeEdgeMessage({
      kind: EdgeMessageKind.Ping,
      sessionId: 'sess-1',
      traceId: 't1',
      payload: { echo: 'hi' },
    })
    expect(m.v).toBe(1)
    expect(m.kind).toBe('ping')
    expect(m.msg_id).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('parses a valid envelope', () => {
    const raw = JSON.stringify({
      v: 1, msg_id: 'x', kind: 'pong', ts: 0, trace_id: 't',
      session_id: 's', payload: { echo: 'hi', server_ts: 1 },
    })
    const m = parseEdgeMessage(raw)
    expect(m).not.toBeNull()
    expect(m!.kind).toBe('pong')
  })

  it('rejects junk', () => {
    expect(parseEdgeMessage('not json')).toBeNull()
    expect(parseEdgeMessage('{}')).toBeNull()
  })

  it('treats unknown kinds as Unknown', () => {
    const raw = JSON.stringify({
      v: 1, msg_id: 'x', kind: 'future.thing', ts: 0, trace_id: 't',
      session_id: 's', payload: {},
    })
    const m = parseEdgeMessage(raw)
    expect(m).not.toBeNull()
    expect(m!.kind).toBe('__unknown__')
  })
})
```

**Step 2: Run, see compile failure**

```bash
cd mateclaw-extension && pnpm test edge-protocol
```
Expected: file not found.

**Step 3: Implement `edge-protocol.ts`**

```ts
// Canonical Edge protocol mirror for the Extension.
// See ../../../docs/specs/edge-protocol.md.

export const EdgeMessageKind = {
  Hello: 'hello',
  HelloAck: 'hello.ack',
  Heartbeat: 'heartbeat',
  HeartbeatAck: 'heartbeat.ack',
  Ping: 'ping',
  Pong: 'pong',
  Error: 'error',
  Unknown: '__unknown__',
} as const
export type EdgeMessageKind = (typeof EdgeMessageKind)[keyof typeof EdgeMessageKind]

const knownKinds = new Set<string>(Object.values(EdgeMessageKind))

export interface EdgeMessage {
  v: 1
  msg_id: string
  kind: EdgeMessageKind
  ts: number
  trace_id: string
  session_id: string
  in_reply_to?: string
  payload?: Record<string, unknown>
}

export function makeEdgeMessage(p: {
  kind: EdgeMessageKind
  sessionId?: string
  traceId?: string
  inReplyTo?: string
  payload?: Record<string, unknown>
}): EdgeMessage {
  return {
    v: 1,
    msg_id: crypto.randomUUID(),
    kind: p.kind,
    ts: Date.now(),
    trace_id: p.traceId ?? crypto.randomUUID(),
    session_id: p.sessionId ?? '',
    in_reply_to: p.inReplyTo,
    payload: p.payload ?? {},
  }
}

export function parseEdgeMessage(raw: string): EdgeMessage | null {
  let obj: unknown
  try {
    obj = JSON.parse(raw)
  } catch {
    return null
  }
  if (
    !obj || typeof obj !== 'object' ||
    (obj as any).v !== 1 ||
    typeof (obj as any).msg_id !== 'string' ||
    typeof (obj as any).kind !== 'string'
  ) {
    return null
  }
  const m = obj as EdgeMessage
  if (!knownKinds.has(m.kind)) {
    return { ...m, kind: EdgeMessageKind.Unknown }
  }
  return m
}
```

**Step 4: Run, confirm pass**

```bash
pnpm test edge-protocol
```
Expected: 4 tests pass.

**Step 5: Commit**

```bash
git add mateclaw-extension/src/shared/edge-protocol.ts \
        mateclaw-extension/src/shared/edge-protocol.test.ts
git commit -m "$(cat <<'EOF'
feat(extension): add Edge protocol TS types mirror

Matches vip.mate.browser.edge.protocol and Go edgeproto.
Unknown wire kinds collapse to EdgeMessageKind.Unknown.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task C4: Service Worker — Native Messaging port

**Files:**
- Create: `mateclaw-extension/src/sw/index.ts`
- Create: `mateclaw-extension/src/sw/native-bridge.ts`
- Test: `mateclaw-extension/src/sw/native-bridge.test.ts`

**Step 1: Write the failing test (mocks `chrome.runtime.connectNative`)**

```ts
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { NativeBridge } from './native-bridge'
import { EdgeMessageKind, makeEdgeMessage } from '../shared/edge-protocol'

describe('NativeBridge', () => {
  const port = {
    onMessage: { addListener: vi.fn(), removeListener: vi.fn() },
    onDisconnect: { addListener: vi.fn() },
    postMessage: vi.fn(),
    disconnect: vi.fn(),
  }

  beforeEach(() => {
    ;(globalThis as any).chrome = {
      runtime: {
        connectNative: vi.fn(() => port),
        lastError: undefined,
      },
    }
    vi.clearAllMocks()
  })

  it('connects to the named native host', () => {
    new NativeBridge('vip.mate.browser_bridge').connect()
    expect((globalThis as any).chrome.runtime.connectNative).toHaveBeenCalledWith('vip.mate.browser_bridge')
  })

  it('forwards messages via postMessage', () => {
    const b = new NativeBridge('vip.mate.browser_bridge')
    b.connect()
    const m = makeEdgeMessage({ kind: EdgeMessageKind.Ping, payload: { echo: 'x' } })
    b.send(m)
    expect(port.postMessage).toHaveBeenCalledWith(m)
  })

  it('delivers inbound messages to listeners', () => {
    const b = new NativeBridge('vip.mate.browser_bridge')
    b.connect()
    const cb = vi.fn()
    b.onMessage(cb)

    const inbound = port.onMessage.addListener.mock.calls[0]?.[0]
    expect(inbound).toBeDefined()
    inbound!({ v: 1, msg_id: 'x', kind: 'pong', ts: 0, trace_id: 't', session_id: 's', payload: {} })

    expect(cb).toHaveBeenCalled()
    expect(cb.mock.calls[0]?.[0]?.kind).toBe('pong')
  })
})
```

**Step 2: Run, expect failure**

```bash
pnpm test native-bridge
```

**Step 3: Implement `native-bridge.ts`**

```ts
// NativeBridge wraps chrome.runtime.connectNative so the rest of the SW
// works in terms of EdgeMessages. Reconnect semantics added in C5.

import { EdgeMessage, parseEdgeMessage } from '../shared/edge-protocol'

export class NativeBridge {
  private port: chrome.runtime.Port | null = null
  private listeners = new Set<(m: EdgeMessage) => void>()
  private disconnectCbs = new Set<() => void>()

  constructor(private readonly hostName: string) {}

  connect(): void {
    const port = chrome.runtime.connectNative(this.hostName)
    if (chrome.runtime.lastError) {
      throw new Error(`connectNative failed: ${chrome.runtime.lastError.message}`)
    }
    this.port = port

    port.onMessage.addListener((raw: unknown) => {
      const m = typeof raw === 'string' ? parseEdgeMessage(raw)
              : parseEdgeMessage(JSON.stringify(raw))
      if (!m) return
      this.listeners.forEach(cb => cb(m))
    })
    port.onDisconnect.addListener(() => {
      this.port = null
      this.disconnectCbs.forEach(cb => cb())
    })
  }

  send(m: EdgeMessage): void {
    if (!this.port) throw new Error('NativeBridge.send: not connected')
    this.port.postMessage(m)
  }

  onMessage(cb: (m: EdgeMessage) => void): () => void {
    this.listeners.add(cb)
    return () => this.listeners.delete(cb)
  }

  onDisconnect(cb: () => void): void { this.disconnectCbs.add(cb) }

  disconnect(): void { this.port?.disconnect(); this.port = null }
}
```

**Step 4: Wire `sw/index.ts`**

```ts
import { NativeBridge } from './native-bridge'

const HOST = 'vip.mate.browser_bridge'
const bridge = new NativeBridge(HOST)

try {
  bridge.connect()
} catch (e) {
  console.error('[mateclaw][sw] connectNative failed', e)
}

bridge.onMessage(m => {
  // Forward inbound Edge messages to whoever is listening (sidepanel etc).
  chrome.runtime.sendMessage({ kind: 'edge.inbound', message: m }).catch(() => { /* no listeners ok */ })
})

chrome.runtime.onMessage.addListener((req, _sender, sendResponse) => {
  if (req?.kind === 'edge.outbound') {
    try { bridge.send(req.message); sendResponse({ ok: true }) }
    catch (e) { sendResponse({ ok: false, error: String(e) }) }
    return true // async sendResponse
  }
})
```

**Step 5: Run tests, commit**

```bash
pnpm test
```
Expected: pass.

```bash
git add mateclaw-extension/src/sw/
git commit -m "$(cat <<'EOF'
feat(extension): add Service Worker NativeBridge to bridge host

Wraps chrome.runtime.connectNative in an EdgeMessage-typed API
and forwards both directions to/from the sidepanel via
chrome.runtime.sendMessage with kind tags.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task C5: Sidepanel UI (Vue 3) — ping button & log

**Files:**
- Create: `mateclaw-extension/src/sidepanel/index.ts`
- Create: `mateclaw-extension/src/sidepanel/App.vue`
- Test: `mateclaw-extension/src/sidepanel/App.test.ts`

**Step 1: Write the failing component test**

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

beforeEach(() => {
  ;(globalThis as any).chrome = {
    runtime: {
      sendMessage: vi.fn().mockResolvedValue({ ok: true }),
      onMessage: { addListener: vi.fn() },
    },
  }
})

describe('Sidepanel App', () => {
  it('renders the ping button', () => {
    const w = mount(App)
    expect(w.find('button[data-test=ping]').exists()).toBe(true)
  })

  it('sends an edge.outbound on click', async () => {
    const w = mount(App)
    await w.find('button[data-test=ping]').trigger('click')
    expect((globalThis as any).chrome.runtime.sendMessage).toHaveBeenCalled()
    const arg = (globalThis as any).chrome.runtime.sendMessage.mock.calls[0][0]
    expect(arg.kind).toBe('edge.outbound')
    expect(arg.message.kind).toBe('ping')
  })

  it('logs inbound messages', async () => {
    const w = mount(App)
    const handler = (globalThis as any).chrome.runtime.onMessage.addListener.mock.calls[0][0]
    handler({ kind: 'edge.inbound', message: {
      v: 1, msg_id: 'x', kind: 'pong', ts: 0, trace_id: 't',
      session_id: 's', payload: { echo: 'yo', server_ts: 1 },
    }})
    await w.vm.$nextTick()
    expect(w.text()).toContain('pong')
    expect(w.text()).toContain('yo')
  })
})
```

Add `@vue/test-utils` to devDeps: `pnpm add -D @vue/test-utils @vue/compiler-sfc happy-dom`. Add `test.environment: 'happy-dom'` to vite/vitest config.

**Step 2: Run, expect failure**

```bash
pnpm test sidepanel
```

**Step 3: Implement `App.vue`**

```vue
<template>
  <div class="root">
    <h1>MateClaw Browser Agent</h1>
    <button data-test="ping" @click="ping">Send ping</button>
    <ul class="log">
      <li v-for="(l, i) in log" :key="i">
        <strong>{{ l.kind }}</strong> {{ l.summary }}
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { EdgeMessage, EdgeMessageKind, makeEdgeMessage } from '../shared/edge-protocol'

interface LogEntry { kind: string; summary: string }
const log = ref<LogEntry[]>([])

function append(e: LogEntry) {
  log.value = [...log.value, e].slice(-100)
}

async function ping() {
  const msg = makeEdgeMessage({
    kind: EdgeMessageKind.Ping,
    payload: { echo: `manual-${Date.now()}` },
  })
  await chrome.runtime.sendMessage({ kind: 'edge.outbound', message: msg })
  append({ kind: 'ping', summary: `echo=${msg.payload?.echo}` })
}

onMounted(() => {
  chrome.runtime.onMessage.addListener((req: unknown) => {
    const r = req as { kind?: string; message?: EdgeMessage }
    if (r?.kind !== 'edge.inbound' || !r.message) return
    const m = r.message
    append({ kind: m.kind, summary: JSON.stringify(m.payload ?? {}) })
  })
})
</script>

<style scoped>
.root { font: 14px/1.4 system-ui; padding: 12px; }
.log { list-style: none; padding: 0; }
.log li { padding: 4px 0; border-bottom: 1px solid #eee; }
</style>
```

`src/sidepanel/index.ts`:

```ts
import { createApp } from 'vue'
import App from './App.vue'
createApp(App).mount('#app')
```

**Step 4: Run, confirm pass**

```bash
pnpm test sidepanel
```
Expected: 3 tests pass.

**Step 5: Commit**

```bash
git add mateclaw-extension/src/sidepanel/ mateclaw-extension/package.json mateclaw-extension/pnpm-lock.yaml
git commit -m "$(cat <<'EOF'
feat(extension): add sidepanel UI with manual ping button

Sends edge.outbound ping on click; logs inbound edge.inbound
messages from the SW. Minimal scaffold for the Phase-1
end-to-end smoke test.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task D1: Native Host install manifest (per-OS)

**Files:**
- Create: `mateclaw-browser-bridge/install/manifest/com.mateclaw.browser_bridge.json`
- Create: `mateclaw-browser-bridge/install/install-windows.ps1`
- Create: `mateclaw-browser-bridge/install/install-macos.sh`
- Create: `mateclaw-browser-bridge/install/install-linux.sh`

**Step 1: Write the manifest template**

`install/manifest/com.mateclaw.browser_bridge.json`:

```json
{
  "name": "com.mateclaw.browser_bridge",
  "description": "MateClaw Browser Agent Native Host",
  "path": "__BRIDGE_BINARY_PATH__",
  "type": "stdio",
  "allowed_origins": [
    "chrome-extension://__EXTENSION_ID__/"
  ]
}
```

Note: The `name` here must match exactly what the Extension passes to
`chrome.runtime.connectNative`. **The Extension uses `vip.mate.browser_bridge`
in C4 — fix that to `com.mateclaw.browser_bridge` (or change this file) so the
two agree. Pick one canonical name in this task and update the other.**

We will rename Extension code in this task — Chrome NM convention is
reverse-DNS, so `com.mateclaw.browser_bridge` wins.

**Step 2: Modify Extension to use the canonical name**

Edit `mateclaw-extension/src/sw/index.ts`:

```ts
const HOST = 'com.mateclaw.browser_bridge'
```

Edit `mateclaw-extension/src/sw/native-bridge.test.ts` likewise.

**Step 3: Install scripts (Windows)**

`install/install-windows.ps1`:

```powershell
# Installs the MateClaw Native Messaging host manifest into the per-user
# Chrome registry hive on Windows.
# Run from an Administrator PowerShell only if installing for All Users.
param(
  [Parameter(Mandatory=$true)][string]$BridgePath,
  [Parameter(Mandatory=$true)][string]$ExtensionId
)

$ErrorActionPreference = 'Stop'
$manifestDir = "$env:LOCALAPPDATA\MateClaw"
New-Item -ItemType Directory -Force -Path $manifestDir | Out-Null

$tpl = Get-Content -Raw "$PSScriptRoot\manifest\com.mateclaw.browser_bridge.json"
$out = $tpl `
  -replace '__BRIDGE_BINARY_PATH__', ($BridgePath -replace '\\','\\') `
  -replace '__EXTENSION_ID__', $ExtensionId
$manifestPath = "$manifestDir\com.mateclaw.browser_bridge.json"
$out | Set-Content -Encoding utf8 $manifestPath

$regKey = 'HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.mateclaw.browser_bridge'
New-Item -Path $regKey -Force | Out-Null
Set-ItemProperty -Path $regKey -Name '(Default)' -Value $manifestPath

Write-Host "Installed MateClaw Native Host:"
Write-Host "  manifest:    $manifestPath"
Write-Host "  binary:      $BridgePath"
Write-Host "  extension:   $ExtensionId"
```

**Step 4: macOS / Linux install scripts (analogous)**

`install/install-macos.sh`:

```bash
#!/usr/bin/env bash
# Installs the MateClaw NM host manifest for Chrome on macOS.
set -euo pipefail

BRIDGE_PATH="${1:?usage: install-macos.sh /abs/path/to/bridge EXTENSION_ID}"
EXTENSION_ID="${2:?usage: install-macos.sh /abs/path/to/bridge EXTENSION_ID}"

DST="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
mkdir -p "$DST"

sed -e "s|__BRIDGE_BINARY_PATH__|${BRIDGE_PATH}|g" \
    -e "s|__EXTENSION_ID__|${EXTENSION_ID}|g" \
    "$(dirname "$0")/manifest/com.mateclaw.browser_bridge.json" \
    > "$DST/com.mateclaw.browser_bridge.json"

echo "Installed to: $DST/com.mateclaw.browser_bridge.json"
```

`install/install-linux.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
BRIDGE_PATH="${1:?usage: install-linux.sh /abs/path/to/bridge EXTENSION_ID}"
EXTENSION_ID="${2:?usage: install-linux.sh /abs/path/to/bridge EXTENSION_ID}"
DST="$HOME/.config/google-chrome/NativeMessagingHosts"
mkdir -p "$DST"
sed -e "s|__BRIDGE_BINARY_PATH__|${BRIDGE_PATH}|g" \
    -e "s|__EXTENSION_ID__|${EXTENSION_ID}|g" \
    "$(dirname "$0")/manifest/com.mateclaw.browser_bridge.json" \
    > "$DST/com.mateclaw.browser_bridge.json"
echo "Installed to: $DST/com.mateclaw.browser_bridge.json"
```

`chmod +x install/install-macos.sh install/install-linux.sh`

**Step 5: Commit**

```bash
git add mateclaw-browser-bridge/install/ \
        mateclaw-extension/src/sw/index.ts \
        mateclaw-extension/src/sw/native-bridge.test.ts
git commit -m "$(cat <<'EOF'
feat(browser-bridge): add Chrome Native Messaging install manifests

Per-OS install scripts (Windows registry / macOS&Linux file)
that drop the com.mateclaw.browser_bridge manifest into the
right Chrome path. Renames the extension's connectNative target
to the canonical reverse-DNS name.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task D2: End-to-end manual smoke test runbook

**Files:**
- Create: `mateclaw-browser-bridge/docs/SMOKE-TEST.md`

This is the "human runs through this once before merging" checklist. Not a code task, but it's part of the acceptance bar.

**Step 1: Write the runbook**

```markdown
# Phase 1 End-to-End Smoke Test

Prereqs:
1. `mateclaw-server` running on `localhost:18088` with the dev profile
   (`mvn spring-boot:run` from `mateclaw-server/`).
2. A valid JWT or PAT with the `browser:edge` scope (PAT created via
   Settings → Security → Personal Access Tokens).
3. Chrome 116+.

## Step 1 — Build the Native Host

    cd mateclaw-browser-bridge
    make build
    # produces ./bin/bridge

## Step 2 — Load the Extension unpacked

1. `cd mateclaw-extension && pnpm build`
2. Open `chrome://extensions`, enable Developer Mode.
3. Click "Load unpacked", select `mateclaw-extension/dist/`.
4. Note the assigned Extension ID (e.g. `abcdefghijklmnop...`).

## Step 3 — Install the NM manifest

Windows (PowerShell):

    .\mateclaw-browser-bridge\install\install-windows.ps1 `
      -BridgePath "D:\devfive\mateclaw\mateclaw-browser-bridge\bin\bridge.exe" `
      -ExtensionId "abcdefghijklmnop..."

macOS / Linux: use `install-macos.sh` / `install-linux.sh` with the same args.

## Step 4 — Configure the bridge

Create `~/.mateclaw/bridge.yaml`:

    control_plane_url: ws://localhost:18088/api/v1/browser/edge
    auth_token: <paste-PAT-or-JWT-here>
    agent_version: 0.1.0

## Step 5 — Trigger the ping flow

1. Open the side panel in Chrome (click the extension icon).
2. Click "Send ping".
3. Expected log entry (within 1 s):

       ping echo=manual-<ts>
       pong {"echo":"manual-<ts>","server_ts":<ts>}

## Step 6 — Verify Control Plane saw the session

    curl -H "Authorization: Bearer <jwt>" \
         http://localhost:18088/api/v1/browser/sessions

Should return a JSON array with one entry whose `userId` matches your
token's principal. (Endpoint added in a follow-up task D3.)

## Step 7 — Heartbeat survives idle

Leave the side panel open with no interaction for 60 s. Check
`mateclaw-server` logs — you should see periodic heartbeat receipts and
no session reaper warning. If a `reaped stale session` line appears,
heartbeats are not getting through. Debug:

    tail -f mateclaw-server/logs/mateclaw.log | grep -i edge

## Step 8 — Kill the Native Host

Find the `bridge` process and kill it. The side panel will log a
disconnect within ~5 s. Re-launch via reopening the side panel and
verify reconnect (a new session id appears on the server).
```

**Step 2 — Commit**

```bash
git add mateclaw-browser-bridge/docs/SMOKE-TEST.md
git commit -m "$(cat <<'EOF'
docs(browser-bridge): add Phase-1 end-to-end smoke test runbook

Eight-step procedure to verify the full Sidepanel→SW→NH→CP
pipeline before merging. Includes heartbeat & reconnect checks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task D3: Sessions debug endpoint

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/BrowserSessionDebugController.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/BrowserSessionDebugControllerTest.java`

The smoke runbook calls a `/api/v1/browser/sessions` GET. Add it.

Returns a JSON array of live sessions backed by `BrowserSessionRegistry.snapshot()`
(added in A3). Used by the smoke runbook to confirm that the Native Host's
connection is visible server-side.

**Step 1: Write the failing tests**

```java
package vip.mate.browser.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrowserSessionDebugControllerTest {

    @Autowired MockMvc mvc;
    @Autowired BrowserSessionRegistry registry;

    @Test
    void list_emptyRegistry_returns200WithEmptyArray() throws Exception {
        mvc.perform(get("/api/v1/browser/sessions"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void list_oneRegistered_returnsOneEntry() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("ws-test");
        when(ws.isOpen()).thenReturn(true);
        var s = registry.register("alice", ws, "0.1.0");
        try {
            mvc.perform(get("/api/v1/browser/sessions"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(1))
               .andExpect(jsonPath("$[0].sessionId").value(s.getId()))
               .andExpect(jsonPath("$[0].subject").value("alice"))
               .andExpect(jsonPath("$[0].agentVersion").value("0.1.0"))
               .andExpect(jsonPath("$[0].lastHeartbeatAt").exists());
        } finally {
            registry.removeByWs("ws-test");
        }
    }
}
```

> **Codex re-audit hint**: `BrowserSessionRegistry` is now autowired as a real
> Spring bean rather than mocked. The second test registers a real session and
> tears it down in `finally` so it does not pollute the next test class. If
> `SecurityConfig` rejects the request without auth, add `@WithMockUser` on
> the test methods rather than weakening security.

**Step 2: Run, expect 404 / compile-error**

```bash
mvn test -Dtest=BrowserSessionDebugControllerTest
```

**Step 3: Implement the controller**

```java
package vip.mate.browser.edge;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.edge.session.BrowserSessionView;

import java.util.List;

/**
 * Read-only debug endpoint. Admin-only — protected by {@code SecurityConfig}.
 * Phase 1: in-memory view only; Phase 4 will switch to DB-backed queries.
 */
@RestController
@RequestMapping("/api/v1/browser/sessions")
@RequiredArgsConstructor
public class BrowserSessionDebugController {

    private final BrowserSessionRegistry registry;

    @GetMapping
    public List<BrowserSessionView> list() {
        return registry.snapshot();
    }
}
```

**Step 4: Run, confirm tests pass**

```bash
mvn test -Dtest=BrowserSessionDebugControllerTest
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

**Step 5: Commit**

```bash
git add mateclaw-server/src/main/java/vip/mate/browser/edge/BrowserSessionDebugController.java \
        mateclaw-server/src/test/java/vip/mate/browser/edge/BrowserSessionDebugControllerTest.java
git commit -m "$(cat <<'EOF'
feat(browser): GET /api/v1/browser/sessions returns live snapshot

Exposes BrowserSessionRegistry.snapshot() over HTTP for the
smoke runbook and admin debugging. Phase-1 in-memory view; the
returned BrowserSessionView projection deliberately omits the
WebSocket reference so the controller cannot accidentally
leak transport state.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Acceptance checklist (final gate before Phase 2)

Run through this list manually after the last task. Each line must be
checkable with a single command or one screenshot.

**Unit / integration tests**
- [ ] `cd mateclaw-server && mvn -q test` passes with 0 failures.
- [ ] `cd mateclaw-browser-bridge && go test -race ./...` passes (race detector
  catches the residual concurrency mistakes Codex was specifically worried
  about — never merge without this).
- [ ] `cd mateclaw-extension && pnpm test` passes.
- [ ] `cd mateclaw-extension && pnpm build` produces `dist/sidepanel.html`,
  `dist/service-worker.js`, and `dist/manifest.json`. (No `offscreen.html` —
  the offscreen document was moved to Phase 2.)

**Codex-flagged invariants (re-verify each)**
- [ ] **P0-1**: `runner.pumpStdinToEdge` test "OverridesAttackerSuppliedSessionId"
  passes — Native Host overrides extension-supplied session_id.
- [ ] **P0-2**: plan and runbook reference WSS-with-Bearer only; no `mTLS` /
  `client_cert` / CA references survive in any of: header, spec, B2 config,
  D2 runbook, D1 install scripts.
- [ ] **P1-1**: spec table separates HTTP 401/403 (handshake) from WS close
  4401 (post-upgrade revocation, deferred to Phase 4). A4 implementation
  uses HTTP 401, not a WS close, on handshake failure.
- [ ] **P1-2**: A4 imports `vip.mate.auth.service.AuthService` and
  `vip.mate.auth.pat.PersonalAccessTokenService`; no `JwtService.parseUserId`
  / `validateAndGetUserId` survives anywhere. PAT scope enforcement is
  marked with `TODO(phase-3)` exactly once.
- [ ] **P1-3**: registry test `register_concurrentSameSubject_leavesExactlyOneLiveSession`
  asserts `registry.size() == 1` AND `sizeForSubject == 1` under 64
  concurrent registrations. Implementation uses
  `subjectToSession.compute(...)`, not `put(...)` followed by `byId.remove(...)`.
- [ ] **P1-4**: handler tests `otherWsSendsKnownSessionId_returnsBindingMismatch`
  and `principalMismatch_returnsBindingMismatch` both pass. WARN log line
  appears in test output (verify by grep).
- [ ] **P1-5**: Go test `TestRun_ReturnsHeartbeatTimeoutWhenServerStopsAcking`
  passes; `ErrHeartbeatTimeout` is an exported sentinel; only one goroutine
  calls `wsjson.Read` on `c.conn`.
- [ ] **P2**: `BrowserSessionDebugControllerTest.list_oneRegistered_returnsOneEntry`
  passes; D3 controller delegates to `registry.snapshot()`; no `return List.of()`
  stub survives.

**End-to-end smoke (manual)**
- [ ] Smoke runbook §1–§8 (Task D2) executed against a local
  `mvn spring-boot:run` Control Plane.
- [ ] After Step 5 the side panel shows a `pong` log entry within 1s of clicking Ping.
- [ ] After Step 6 `curl /api/v1/browser/sessions` returns a one-element array
  with a valid `subject`, `sessionId`, `agentVersion`, `lastHeartbeatAt`
  (was empty `[]` in the v1.0 draft).
- [ ] After Step 7 (60s idle) `tail mateclaw-server/logs/mateclaw.log` shows
  no `reaped stale session` warning for the active session id.
- [ ] After Step 8 (NH killed), the side panel logs a disconnect; the bridge
  reconnects within 1–2 s with a *new* `sess-...` id (visible in the next
  ping log) — proves the B7 reconnect loop is working end-to-end, not just
  in tests.

**Hygiene**
- [ ] No commit message lacks the `Co-Authored-By: Claude Opus 4.7` trailer.
- [ ] No file outside `vip.mate.browser.*` / `mateclaw-browser-bridge/` /
  `mateclaw-extension/` / `docs/` was modified (verify
  `git diff main --stat` — anything else needs an explicit reason in the PR).
- [ ] `audit-response.md` and this plan agree on the disposition of every
  Codex finding (cross-check by running `grep -E '^(P[0-2]-[0-9])' docs/plans/*`).

---

# Phase 2 preview (NOT in this plan)

After this foundation is reviewed and green:

**Carried over from items deferred during this Codex audit pass:**
- Offscreen Document for SW-kill survival (was C6 in the v1.0 draft). Becomes
  necessary the moment Phase 2's CDP work outlives the 5-minute SW idle
  timer; not before.

**New Phase 2 work:**
- T2.1–T2.9: `chrome.debugger.attach` + 10 atomic CDP actions (navigate,
  click, type, scroll, wait, move_mouse, idle, read_dom, screenshot,
  eval-fenced).
- T2.10: WindMouse + log-normal jitter library (humanise mouse/keyboard).
- T2.11: `action.execute` / `action.result` kinds added to EdgeProtocol;
  receivers must still ignore unknown kinds (forward-compat invariant
  from v1.0 spec stays).
- T2.12: First "open douyin.com and read its title" end-to-end test
  against the smoke harness extended from D2.

**Phase 3 owns** (also surfaced by the audit but not Phase 2):
- mTLS / client cert issuance / rotation for SaaS deployments (P0-2).
- Per-scope authorisation (`browser:edge` PAT scope enforcement, P1-2).
- Post-upgrade revocation check + WS close 4401 emission (P1-1).
- SOP YAML / Synthesizer / Replay / Drift / Adapter.

**Phase 4 owns**:
- DB-backed sessions and reconnect resumption (`hello.resume` in spec).
- SQLite outbox in the Native Host for stateful work survival.
- Long-lived `Long`-typed user identity replacing the Phase-1 `subject` string.

This Phase 1 plan is the prerequisite. Codex audit notes here will shape the
Phase 2 plan generation.
