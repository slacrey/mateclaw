# Audit Response — Browser Agent Foundation (Phase 1)

Codex audit pass: 2 P0 / 5 P1 / 1 P2.
This document records the disposition (accept / amend / reject) of every finding
and pins the exact patches applied to `2026-05-28-browser-agent-foundation.md`.

> **TL;DR**: All 8 findings accepted; 7 plan patches landed. mTLS is explicitly
> downgraded out of Phase 1 scope and pushed to Phase 3 (SaaS hardening) with a
> rationale recorded below.

---

## P0-1 — session_id propagation broken end-to-end

**Status: ACCEPTED. Plan patched.**

**Root cause**: Extension creates `ping` with empty `session_id` (correctly —
extension has no business knowing the server's opaque id). Native Host's
`runner.pumpStdinToEdge` forwarded the message unchanged, so server saw a
`session_id=""` and routed it through `validSession` which rejected with
`app.invalid_session`. The B7 test masked this by hand-setting
`SessionID: "sess-1"` in the test fixture.

**Decision**: Native Host is the **single owner of session_id state**.

- Extension always emits `session_id=""` (kept honest by spec).
- Native Host stamps `session_id = client.SessionID()` on every outbound frame
  that is **not** `hello` (hello itself must have empty session_id, both spec
  and handler depend on this).
- If Extension sends a non-empty `session_id`, Native Host **overrides** it —
  the Extension is not trusted to choose session ids. This is a security
  property: a compromised extension cannot try to address other users'
  sessions by setting their session_id.

**Patches**:
1. `edge-protocol.md` §Reconnect — add an explicit "Native Host is the sole
   owner of `session_id`; clients downstream of NH MUST emit empty string"
   paragraph.
2. Task B7 `runner.pumpStdinToEdge` — set `msg.SessionID = c.SessionID()`
   before write, override-not-trust semantics.
3. Task B7 — replace the hand-coded `SessionID: "sess-1"` test fixture with
   a fixture where Extension-side message has `SessionID: ""` and the test
   asserts the WSS server **sees** the server-issued session id on the
   forwarded ping. Add a second test that proves the override: even if the
   stdin frame has `SessionID: "sess-attacker"`, the WSS server sees the
   bridge's real session id.

---

## P0-2 — mTLS is in the goal but has no implementation path

**Status: ACCEPTED. Phase 1 contract downgraded to Bearer-over-WSS.**

**Rationale**: mTLS made sense in the architecture sketch because production
SaaS deployments will want it. But Phase 1 ships exactly **one** authenticated
channel between *the user's own Native Host* and *the user's own Control
Plane*. The threat model is:

- Local dev: `ws://localhost:18088` — TLS-irrelevant.
- Self-hosted: HTTPS terminated at a reverse proxy (nginx/Caddy/Tailscale) in
  front of the JVM. WSS is then a TLS-protected Bearer channel. The reverse
  proxy + a strong PAT is materially equivalent to mTLS at the Phase-1 scale
  (single user, single Bridge).
- Future multi-tenant SaaS (Phase 3): mTLS adds value because Native Hosts run
  on machines we don't control and we want to refuse stolen-token attacks
  even if the token is exfiltrated. This is correctly Phase-3 work — it
  requires CA infrastructure, cert rotation, hardware-binding policy decisions,
  and a UX for "your machine cert expired".

**Patches**:
1. Plan Goal/Architecture — replace "mTLS WSS" with "WSS with Bearer (JWT or
   PAT); mTLS is Phase 3 scope".
2. Plan adds an explicit "Out-of-scope" entry: "mTLS / client certificate
   issuance / cert rotation" with a forward pointer to Phase 3.
3. `edge-protocol.md` — `auth` block stays the same shape (`scheme`, `token`);
   no `client_cert` field added.
4. Smoke runbook — keep `ws://localhost:18088` for local dev; add a note that
   "production deployments terminate WSS at a reverse proxy with TLS 1.3".

---

## P1-1 — 4401 close-code semantics conflict with handshake auth

**Status: ACCEPTED. Spec rewritten to separate handshake vs post-upgrade auth.**

**Decision**:
- **Handshake-time auth failure**: HTTP **401** (missing/bad Bearer) or **403**
  (PAT lacks `browser:edge` scope when scopes are eventually enforced — Phase 3).
  These are HTTP responses before the WS upgrade completes. `EdgeAuthInterceptor`
  returns `false` and writes status; the browser/Native Host never sees a WS
  frame.
- **Post-upgrade auth loss**: WS close **4401**. Reserved for the case where
  the principal becomes invalid mid-session (token revoked, user disabled,
  PAT rotated). Phase 1 does **not** implement post-upgrade revocation
  checking — it just reserves the code. A periodic revalidation job is Phase 4.

**Patches**:
1. `edge-protocol.md` §Error codes — table now has two columns: "HTTP at
   handshake" and "WS close after upgrade". Reassign 4401 to post-upgrade only.
2. Plan Codex review hot-spot #2 — reword to say "Handshake auth failures
   return HTTP 401/403 before the upgrade; reserve 4401 for post-upgrade
   revocation (not implemented in Phase 1)".

---

## P1-2 — Auth APIs assumed in A4 do not exist

**Status: ACCEPTED. A4 rewritten against real MateClaw APIs.**

**Verified facts** (from this repo):

| Phantom API in plan | Real API |
|---|---|
| `JwtService.parseUserId(String) → Optional<String>` | `AuthService.parseToken(String) → String` (returns subject = username; null on fail) and `AuthService.parseClaims(String) → Claims` (null on fail) |
| `PersonalAccessTokenService.validateAndGetUserId(String, scope) → Optional<String>` | `PersonalAccessTokenService.findActiveByPlaintext(String) → Optional<PersonalAccessTokenEntity>` |
| Per-scope check `browser:edge` | PAT entity has a `scopes` field but `PersonalAccessTokenEntity` Javadoc explicitly says "finer-grained scope checking lands in a follow-up RFC" |

**Decision**:
- JWT path: `authService.parseClaims(token)` → if non-null and not expired,
  pull `subject` (= username). Use as `EdgePrincipal.subject`.
- PAT path: `patService.findActiveByPlaintext(token)` → if present, take
  `entity.userId` (Long), convert to String for the principal field. PAT
  scope check is deferred — Phase 1 accepts any active PAT. Mark with a
  `// TODO(phase-3): enforce browser:edge scope when scope enforcement RFC lands`
  comment so the gap is visible.
- `EdgePrincipal` field is renamed `subject` (string) to honestly reflect that
  Phase 1 carries either a username (JWT) or a userId-as-string (PAT).
  Registry keys by this subject. Long-typed userId joins in Phase 4 when we
  introduce DB-backed sessions.

**Patches**:
1. Task A3 — `BrowserSession.userId` → `BrowserSession.subject` (rename
   throughout, update tests).
2. Task A4 — rewrite both the implementation skeleton and the test mocks
   against the real services. Add an integration test that exercises an
   actual PAT (created via the existing PAT service) and a token-from-real-
   `generateToken` JWT; this catches Codex-class drift in the future.
3. Task A4 — explicit `TODO(phase-3)` for scope enforcement.

---

## P1-3 — Registry register() has a TOCTOU race

**Status: ACCEPTED. Implementation rewritten with atomic compute.**

**Bug**: original code did `userToSession.put(user, newId)` first, then
`byId.put(newId, session)`. Two concurrent reconnects for the same user
interleave like:

```
T1: userToSession.put(u, id1)  → returns null  (no previous)
T2: userToSession.put(u, id2)  → returns id1
T2: byId.remove(id1)           → null (T1 hasn't inserted yet)
T1: byId.put(id1, s1)          → ★ id1 now live
T2: byId.put(id2, s2)          → ★ id2 also live
End state: two byId entries, userToSession points to id2 only.
```

**Fix**: insert the new session into `byId` **first** (so it is the
authoritative source for "what live sessions exist"), then do an atomic
`userToSession.compute(subject, ...)` which returns the previous id; remove
that previous id from `byId` and close its ws inside the compute callback so
all writes are serialised through `userToSession`'s per-key lock.

**Patches**:
1. Task A3 — new `register()` body:
   ```java
   public BrowserSession register(String subject, WebSocketSession ws, String agentVersion) {
       String newId = "sess-" + UUID.randomUUID();
       BrowserSession session = BrowserSession.builder()
               .id(newId).subject(subject).agentVersion(agentVersion).ws(ws)
               .lastHeartbeatAt(clock.instant()).build();
       byId.put(newId, session);   // visible first
       String prevId = userToSession.compute(subject, (k, existing) -> {
           if (existing != null && !existing.equals(newId)) {
               BrowserSession prev = byId.remove(existing);
               if (prev != null) {
                   closeQuietly(prev.getWs(), new CloseStatus(4409, "session-conflict"));
               }
           }
           return newId;
       });
       return session;
   }
   ```
2. Task A3 — add a concurrency test that fires N concurrent `register` calls
   for the same subject from a thread pool and asserts:
   (a) `byId.size() == 1` afterwards
   (b) `userToSession.get(subject)` matches that single id
   (c) all the other ws mocks observed a `close(4409)` call.

---

## P1-4 — Session id is not bound to ws/principal

**Status: ACCEPTED. `validSession` patched to check binding.**

**Vulnerability**: any authenticated edge connection that learns another live
`session_id` can send `ping`/`heartbeat` for it because the handler only
checked existence. Concretely: if user A and user B are both connected, A's
malicious code could send `ping` with B's `session_id` and the server would
process it under B's session.

**Decision**: `validSession(ws, msg)`:
1. Resolve `session = registry.find(msg.sessionId)` — if absent → `app.invalid_session`.
2. Check `session.getWs().getId().equals(ws.getId())` — must be the same
   underlying socket.
3. Check `session.getSubject().equals(principal.subject())` — must be the
   same authenticated principal.
4. Failure of either binding check → emit `app.session_binding_mismatch`
   error (NOT a generic invalid_session, so we can spot abuse in logs) and
   do **not** mutate state.

**Patches**:
1. Task A5 — `validSession` body rewritten with all three checks.
2. Task A5 — add three tests: (a) different ws same principal → mismatch;
   (b) same ws different principal — actually impossible by construction
   since principal is attached to ws, so this becomes a "tampered
   attribute" test that mocks `ws.getAttributes()` to return a wrong
   principal; (c) baseline happy path still passes.

---

## P1-5 — Heartbeat ack monitoring + reconnect not implemented

**Status: ACCEPTED. B5 substantially expanded; reconnect moved to B7 layer.**

**Gap**: original B5 only sent heartbeats. It did NOT track `heartbeat.ack`
arrival, did not apply `hello.ack.heartbeat_interval_ms`, did not close on
30s silence, did not back off on reconnect. Test passed because
`Options.HeartbeatInterval` was manually overridden in the fixture.

**Decision — split responsibility cleanly**:

- **`edge.Client` (B5)** owns *liveness within one connection*:
  - On `hello.ack`, capture `heartbeat_interval_ms` and override
    `opt.HeartbeatInterval` if non-zero.
  - Track `lastAckAt` (updated whenever a `heartbeat.ack` arrives).
  - If `now - lastAckAt > 3 × interval` (default 30s for a 10s interval),
    close the connection with `websocket.StatusGoingAway` and surface a
    typed error `ErrHeartbeatTimeout` on `Run`'s return.
  - Does **not** reconnect. Single-connection lifetime only.

- **`runner.Runner` (B7)** owns *reconnect across connections*:
  - On `Client.Run` returning `ErrHeartbeatTimeout` or any IO error, the
    runner calls `Connect()` again with exponential backoff (1s, 2s, 4s, 8s,
    capped at 60s, plus ±20% jitter).
  - Maximum 5 consecutive failures → give up, log fatal, exit non-zero
    (Chrome will see Native Messaging EOF and surface a disconnect to the
    Extension — that's the correct propagation).
  - On reconnect, gets a fresh `session_id`. Any messages buffered between
    sessions are dropped in Phase 1 (Phase 4 adds the outbox).

**Patches**:
1. Task B5 — extend `Options`/`Client` with `lastAckAt`, `ErrHeartbeatTimeout`,
   override of interval from `hello.ack`. Add tests:
   (a) Server stops acking → client returns `ErrHeartbeatTimeout` within
       `~3 × interval`; (b) `hello.ack.heartbeat_interval_ms=500` overrides
   the default; (c) baseline happy path still passes.
2. Task B7 — new section `pumpReconnect` that wraps the entire `Connect →
   Run → Close` cycle in a loop with backoff. Add a test that uses a
   server which drops the first two connections and accepts the third,
   asserting the runner achieves three connects within the deadline and
   the back-off timing is approximately 1s + 2s.

---

## P2 — Workstream promises missing/stubbed pieces

**Status: ACCEPTED. Plan trimmed; deferred items moved to Phase 2/4.**

**Specific fixes**:

| Promise | Disposition |
|---|---|
| C6 Offscreen WSS fallback | Removed from Phase 1. Moved to Phase 2 (along with `chrome.debugger.attach` which is the actual reason SW gets killed under load). Rationale: SW kill in Phase 1 only loses the ping pipeline, which is recoverable just by reopening the side panel. Offscreen Document is necessary when long-running CDP sessions need to survive SW kill — that's Phase 2's concern. |
| C7 SW ↔ Sidepanel bus as a standalone task | Removed; the bus is implicitly implemented in C4/C5 via `chrome.runtime.sendMessage`. No separate task. Codex was right that the workstream overview listed it but the body didn't deliver it. |
| SQLite outbox in Native Host | Removed from Phase 1 Architecture section. Moved to Phase 4 (state machine + checkpoint), which is the right place — the outbox only matters once we have stateful work to resume. |
| D3 returns `[]` (empty stub) | Replaced with real implementation. Add `BrowserSessionRegistry.snapshot() → Collection<BrowserSessionView>` (a read-only DTO without the `ws` reference) and have the controller return that. Update tests to: (a) empty registry returns `[]`; (b) one registered session returns one entry with subject, sessionId, agentVersion, lastHeartbeatAt. Smoke runbook can now actually observe the session. |

**Patches**:
1. Plan workstream overview ASCII diagram — strike C6 and C7; renumber C
   tasks to C1–C5.
2. Plan Architecture paragraph — strike "SQLite for queued messages".
3. Task D3 — full rewrite including registry snapshot method, controller
   that uses it, and two-case test.
4. Phase 2 preview section — add bullets for the deferred items so they
   are not lost.

---

## Patches applied to the plan

The actual edits land in `2026-05-28-browser-agent-foundation.md` immediately
after this response document is written. Diff summary:

```
plan header                  P0-2  (mTLS → WSS+Bearer)
docs/specs/edge-protocol.md  P0-1, P1-1  (session_id ownership, error codes)
Task A3                       P1-3, P1-2  (race fix + rename to subject)
Task A4                       P1-2  (real Auth APIs)
Task A5                       P1-4  (binding check in validSession)
Task B5                       P1-5  (ack tracking, ErrHeartbeatTimeout)
Task B7                       P0-1, P1-5  (session_id stamp, reconnect loop)
Task C4 / C5                  P2   (drop C6/C7 from workstream)
Task D3                       P2   (real implementation)
Phase 2 preview               P0-2, P2  (deferred items captured)
Acceptance checklist          adds concurrency-test and binding-test entries
```

After these patches, the second Codex pass should focus on:
1. Whether the new `register()` truly forbids the interleaving Codex
   identified (look for `byId.size() == 1` invariant under concurrent calls).
2. Whether the reconnect loop in B7 honors the backoff schedule and stops
   after 5 attempts (look at the test, not the code).
3. Whether `validSession` correctly fails closed when either binding fails
   (look for the test that mocks a mismatched principal).
4. Whether the deferred items in the Phase-2 preview cover all the cuts.
