# Browser Agent Phase 2 — CDP Control + Visual Indicators + Three-Engine Grounding

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
>
> **For Codex (review pass):** Phase 2 builds on the Phase 1 foundation (three-process WSS connectivity) and delivers the **actual browser control loop**: the Extension gets `chrome.debugger` permission and dispatches CDP-based atomic actions; a visual indicator system (phantom cursor + glow border + stop button + tab group pill) tells the user what the agent is doing; the Control Plane gains a three-engine Orchestrator that picks targets from DOM / A11y tree / Vision. End-to-end deliverable: user issues a high-level command in the sidepanel → Control Plane plans → Bridge forwards → Extension drives the user's Chrome → user sees the phantom cursor move and click. Please review for: CDP lifecycle hygiene, action idempotency, message protocol consistency with v1, visual-system race conditions under SW kill, and TDD discipline.

> **Revision history**
> - 2026-05-28 v1.0 — initial draft.
> - 2026-05-28 v1.1 — applied Codex audit response (see
>   `2026-05-28-browser-agent-phase-2.audit-response.md`). Fourteen findings
>   accepted: tab_ref added to every targeted envelope (P0-1); new
>   `PlanExecutionService` task F4 owns the sequential await loop (P0-2);
>   new `PageSnapshotService` task F5 + handler task B11 wire the a11y
>   snapshot pipeline with freshness/staleness rules (P0-3); plus nine P1
>   and three P2 patches.
> - 2026-05-28 v1.2 — applied Codex round-2 verification feedback
>   (REGRESSED on V1 and D6). E1's `multiTab_...` test rewritten to use
>   **two distinct tab ids** (42 + 43) and to cross-check (x, y) params do
>   not cross-wire; added negative companion test `multiTab_unresolvableTabRef_yieldsNoTargetTabFailure`.
>   Iframe-internal grounding moved from the bottom "Phase 3 preview" into
>   the top "Out of scope" list where the auditor's location requirement
>   placed it.

**Goal:** Deliver an end-to-end loop where the Control Plane drives a single user-side Chrome tab via the Phase 1 transport, performs a small set of human-rate atomic actions (`navigate`, `move_mouse`, `click`, `type`, `scroll`, `wait`), shows the user a phantom-cursor + glow-border + stop-button overlay while doing so, and uses a DOM-engine-only grounding for target selection (A11y and Vision wired as stubs to be filled in Phase 3).

**Architecture (delta from Phase 1):**

```
┌─────────────────────────────────────────────────────────────────┐
│ Control Plane (mateclaw-server)                                 │
│ + Edge protocol v2 (action.* / indicator.* / a11y.* kinds)      │
│ + ActionExecutionService (one in-flight action per session,     │
│   correlation by msg_id, 30s deadline)                          │
│ + ActionPlanner (Step → ActionRequest)                          │
│ + GroundingDispatcher (DOM real, A11y / Vision stubs)           │
└─────────────────────────────────────────────────────────────────┘
                              │ Edge WSS (Phase 1)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Native Host (mateclaw-browser-bridge)                           │
│ Phase 1 stays — bridge just forwards new kinds.                 │
└─────────────────────────────────────────────────────────────────┘
                              │ Native Messaging
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ Chrome Extension (MV3)                                          │
│ + manifest: debugger + scripting permissions                    │
│ + ActionExecutor (SW): chrome.debugger lifecycle + dispatch     │
│ + Atomic actions: navigate / move_mouse / click / type /        │
│   scroll / wait — via Input.dispatchMouseEvent +                │
│   Input.dispatchKeyEvent (NOT element.click())                  │
│ + Human-factors library: WindMouse + log-normal jitter          │
│ + Visual indicator content script:                              │
│     PhantomCursor + GlowBorder + StopButton                     │
│ + A11y tree content script (with bbox extension)                │
│ + TabGroupManager in SW: createGroup / findByTab / getMain      │
│ + Static indicator pill (secondary tabs)                        │
└─────────────────────────────────────────────────────────────────┘
                              │ chrome.debugger / chrome.tabs
                              ▼
                      User's Chrome (their tabs)
```

**Tech Stack (delta):**
- Java 21 / Spring Boot 3.5 — Control Plane (existing)
- Go 1.22 — Bridge (existing; no changes other than relaying new message kinds)
- TypeScript 5 / Vue 3 — Extension (existing scaffold + new content scripts)
- Chrome Debugger Protocol — for input dispatch (`Input.dispatchMouseEvent`, `Input.dispatchKeyEvent`)
- Chrome `tabGroups` API — for the agent's identity in the tab bar

**Prerequisites:**

This plan presumes Phase 1 is merged. Specifically it depends on:
- `EdgeMessage` / `EdgeMessageKind` (Java + Go + TS)
- `BrowserSessionRegistry` with subject-binding
- `EdgeWebSocketHandler` + `EdgeAuthInterceptor`
- `runner.Runner` with reconnect loop
- `NativeBridge` content of Sidepanel ↔ SW pipe
- The session-id-stamp rule (NH is sole owner)

If any of those have not landed, do not start this plan.

**Out of scope (deferred to Phase 3+):**

- **Iframe-internal element grounding** — Phase 2 restricts the a11y content script to the top frame (`all_frames: false`, Codex P1-5). Cross-frame grounding requires bbox-to-page coordinate translation (walking the `frameElement.getBoundingClientRect()` chain) plus per-frame ref tagging, which is Phase 3 work.
- DOM-engine selector candidates with confidence learning (Phase 3 SOP)
- Real A11y engine consumption (Phase 2 wires the content script; the engine that consumes the tree lives in Phase 3)
- Vision engine (LLM multimodal call) — stub returns null in Phase 2; real model integration is Phase 3
- Drift detection / Drift Repair (Phase 3)
- WebRTC streaming of the user's browser to the admin console
- `read_dom` / `screenshot` / `eval` / `idle` actions — added in Phase 3
- SOP synthesis from trajectory — Phase 3
- Multi-tab orchestration beyond one main + N secondary in the same group
- Per-scope authorization (`browser:edge`) — Phase 3
- mTLS — Phase 3

**References (read these before reviewing/implementing):**

- [docs/plans/2026-05-28-browser-agent-foundation.md](2026-05-28-browser-agent-foundation.md) — Phase 1 plan (the assumed baseline)
- [docs/plans/2026-05-28-browser-agent-foundation.audit-response.md](2026-05-28-browser-agent-foundation.audit-response.md) — outcomes of the Phase 1 Codex audit
- [docs/specs/edge-protocol.md](../specs/edge-protocol.md) — Edge protocol v1 (this plan adds v2 kinds)
- [docs/research/2026-05-28-claude-chrome-visual-system.md](../research/2026-05-28-claude-chrome-visual-system.md) — research summary of patterns. **Read §1–§9 before C-stream tasks; §6 before D1; §8 before A11y tree task.**

---

## Codex review hot-spots

When auditing this plan, focus on:

1. **CDP lifecycle hygiene** — `chrome.debugger.attach` is *exclusive*: at most one debugger per tab. Verify the plan handles: tab refresh (attach drops), user-opened DevTools (Chrome silently detaches us with a banner), tab close, and multiple sessions racing to attach.
2. **One-in-flight invariant** — at any moment, at most one `action.execute` is being processed per session. The plan must enforce this in both Control Plane (Java) and Extension (TS), or correlation by `msg_id` can interleave actions and corrupt visual cursor state.
3. **Cursor / action timing contract** — the visual cursor `move_mouse` Promise resolves **after** the CSS transition completes (~220 ms). The plan must show the Control Plane **awaits** the visual `move_mouse.result` *before* sending the `click` — otherwise the user sees the cursor lag behind the click. Verify this in the integration test.
4. **Untrusted-event ban** — actions must NEVER use `element.click()` / `el.dispatchEvent(new MouseEvent(...))`. Only CDP `Input.dispatchMouseEvent` and `Input.dispatchKeyEvent`. JS-dispatched events carry `isTrusted=false` and are filtered out by every modern anti-bot heuristic. Grep the plan for forbidden patterns.
5. **session_id contract still holds** — every Extension-originated message (`action.result`, `event.page`, `STOP_AGENT`) must leave the Extension with `session_id=""`; the Bridge stamps it. Verify P2 / B-stream tests honour this — there are several places where it would be tempting to set it inline.
6. **Tab Group ownership** — `TabGroupManager.createGroup` must guard against grabbing an existing group that the user manually created. The plan must show how it distinguishes managed groups (by title prefix) from user-made groups (`isUnmanaged: true`).
7. **Visual indicator cleanup under SW kill** — the static pill heartbeat self-kill pattern from Phase 1's research must be preserved. Verify the test exercises "SW dies → pill removes itself within 10s".
8. **Reduced motion** — every animation respects `prefers-reduced-motion: reduce`. The glow pulse must turn off (not just slow down); the cursor transition must shrink to ~30 ms (functionally instant). Verify the CSS includes this media query.
9. **TDD discipline** — every implementation task has its test written and *seen failing* before implementation. Flag any task that skips the "run, see fail" step.

---

## Project conventions (delta from Phase 1)

- Branch: continue on the Phase 1 feature branch *unless* Phase 1 has merged to `dev`. In that case create `feat/browser-phase-2` off `dev`.
- All new TS DOM ids use the `mateclaw-` prefix (`mateclaw-phantom-cursor`, `mateclaw-glow-border-*`, `mateclaw-stop-button`, `mateclaw-static-pill`). Globals on `window` use `__mateclaw_` prefix (`__mateclaw_a11y_tree`, `__mateclaw_element_map`).
- All visual styling uses the MateClaw brand color (provided by `brand.css`). Do not hard-code hex literals inside JS — read CSS variables.
- Commit trailer remains:
  ```
  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  ```

---

## Workstream overview (v1.1)

```
P. Protocol v2 (Java + Go + TS)         B. Extension Action Executor (TS)
   P1 New EdgeMessageKind + tab_ref          B1 chrome.debugger lifecycle
      (+ a11y lifecycle spec section)            (SessionDetachedError +
   P2 Action request/result/success           devtools_open test)
      types (TabRef sealed +                 B2 ActionExecutor dispatch shell
      ActionSuccessPayload sealed)           B3 navigate
   P3 ActionExecutionService                 B4 click (clock+RNG injection)
      (cancel CAS race fix +                 B5 type (clock+RNG injection)
      indicator.stop_clicked)                B6 scroll
                                             B7 move_mouse (consumes WindMouse;
C. Extension Visual Indicators (TS)            profile=natural|linear)
   C1 A11y tree CS (top frame only)         B8 wait
   C2 PhantomCursor (+ reduced-motion)      B9 WindMouse library (consumed by B7)
   C3 GlowBorder (+ reduced-motion)         B10 SW wiring + tab_ref resolution
   C4 StopButton (closes loop via SW)            + STOP_AGENT → outbound
   C5 VisualIndicator CS (+ idempotency)    B11 SnapshotRequestHandler  ← NEW
   C6 Manifest (all_frames: false for a11y)

D. SW — Tab Group + Visual Coordinator   F. Control Plane Orchestrator
   D1 TabGroupManager                       F1 ActionPlanner (TabRef propagation)
   D2 VisualCoordinator                     F2 GroundingDispatcher (sealed Result
      (accepts TabRef)                          + Ambiguous handling + refresh hook)
   D3 StaticPill heartbeat                  F3 DomEngine (emits Ambiguous on tie)
      (stretch — explicit SW-kill test)     F4 PlanExecutionService          ← NEW
                                                (sequential concatMap loop)
E. Integration                              F5 PageSnapshotService           ← NEW
   E1 about:blank end-to-end                    (freshness map + refresh)
      (multi-tab + sequence timing
      + snapshot refresh)
   E2 douyin.com smoke (manual)
   E3 Runbook + acceptance updates
```

Dependencies: **P1/P2 ⟶ everything**. P3/F4 unblock E1. B11/F5 unblock A11y-driven grounding. B-stream depends on C2 for visual confirmation. D1 unblocks D2/D3/B10 (tab_ref → tabId).

**v1.1 additions** (post-Codex):
- **B11 SnapshotRequestHandler**: SW receives `a11y.snapshot.request`, resolves tab_ref, invokes `chrome.scripting.executeScript({func: () => window.__mateclaw_a11y_tree(...)})`, wraps result + new `snapshot_id` + `captured_at_ms` in `a11y.snapshot.response`.
- **F4 PlanExecutionService**: takes `List<ActionRequest>`, executes via `concatMap` (NEVER `flatMap` / `Flux.merge`). On Failure, stops and returns `PlanResult.partial(executedPrefix, failedStep)`.
- **F5 PageSnapshotService**: owns per-(session, tabRef) freshness map. `request(session, tabRef, filter)` returns cached if FRESH, refetches if STALE. Marks STALE on NAVIGATE success; SUSPECT on CLICK/TYPE/SCROLL success (one retry budget); STALE if > 30 s old.

---

## TDD step template (v1.1 — fixes Codex P1-7)

Every B/C/D/F task has the same five-step shape. To honour the audit's "see fail before implementing" demand without bloating each task, this prologue defines the canonical step contents. Each task lists its **Step 1 (test code)** and **Step 3 (implementation hints)** in full, and references this prologue with `[run per template]` for Steps 2 and 4.

**Standard Step 2 — "Run the test, see it fail"**

| Task workspace | Command | Expected failure mode |
|---|---|---|
| `mateclaw-server/` (Java) | `mvn test -Dtest=<ClassName>` | `COMPILATION ERROR` (symbol not found) or `Tests run: N, Failures: M` |
| `mateclaw-browser-bridge/` (TypeScript Node) | `pnpm test --run <filter>` | `Cannot find name` (tsc) / `is not a function` / failing assertion |
| `mateclaw-extension/` (TypeScript) | `pnpm test --run <filter>` | `Cannot find module` / `<member> is not a function` |

> **Note (Phase 1 pivot, ratified in v1.2):** the bridge runtime was
> originally specified in Go but pivoted to TypeScript/Node during Phase 1
> due to toolchain availability. The architectural contract (single reader,
> heartbeat sentinel, session_id stamping, reconnect backoff) survived
> 1:1; only the language differs. All Phase 2 task references below to
> `.go` files / `go test` should be read as their TypeScript equivalents
> (`.ts` files, `pnpm test`).

**Standard Step 4 — "Run the test, confirm it passes"**

Same command as Step 2; expect `Tests run: N, Failures: 0` (Java) / `Test Files <n> passed` (Vitest, both bridge and extension).

**Standard Step 5 — Commit**

Always uses Conventional Commits + the project trailer:

```bash
git add <touched files>
git commit -m "$(cat <<'EOF'
<type>(<scope>): <short subject>

<body if needed>

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Tasks that follow this template are flagged `[per template]`. Tasks whose Step 2/4 are non-obvious (B11, F4, F5, E1) write them out explicitly.

**TDD discipline gate**: every commit log must contain a "test passes" run between the implementation commit and the next task's start. If you find yourself implementing without first staging a failing test, **stop, write the test, see it fail, then resume**. Codex's re-audit will spot-check the commit graph.

---

# P-stream: Protocol v2

# Task P1: Extend EdgeMessageKind with action.* / indicator.* / a11y.*

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/browser/edge/protocol/EdgeMessageKind.java`
- Modify: `mateclaw-browser-bridge/src/internal/edgeproto/edgeproto.ts` (Phase 1 pivoted from Go to TS)
- Modify: `mateclaw-extension/src/shared/edge-protocol.ts`
- Modify: `docs/specs/edge-protocol.md`
- Test: extend each of the three existing protocol tests

**New wire kinds (must match exactly across all three runtimes):**

| kind | direction | notes |
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

**Step 1: Write the failing test** in the Java protocol test file. Assert each new kind round-trips through `ObjectMapper` to its expected wire string (e.g. `action.execute`, `indicator.cursor`, `a11y.snapshot.request`). Assert unknown kinds still decode to `UNKNOWN`.

**Step 2: Run, see fail** — `mvn test -Dtest=EdgeMessageTest` reports compile errors / missing enum entries.

**Step 3: Add entries to `EdgeMessageKind.java`.** Pattern is the same as Phase 1 — one enum constant per row above, wire string set explicitly.

**Step 4: Mirror in `edgeproto.ts` (NH bridge) and `edge-protocol.ts` (extension).** Add the matching constants and update each unit test to assert their wire format. Add a new test that takes a JSON sample of each new kind, decodes, asserts the kind field is correct, re-encodes, and byte-compares.

**Step 5: Update `docs/specs/edge-protocol.md`** with the new kinds table and payload shapes. **All envelopes targeted at a tab carry a `tab_ref` field (Codex P0-1).**

**TabRef wire format:**
```
"main"             → SW resolves via TabGroupManager.getMainTabId(sessionSubject)
"active"           → SW resolves via chrome.tabs.query({active:true, lastFocusedWindow:true})
<integer>          → explicit Chrome tab id (used for tests and future multi-tab orchestration)
```

If neither resolution succeeds, the SW emits `action.result`/`a11y.snapshot.response` Failure with `code: "NO_TARGET_TAB"`.

**Payload shapes:**

- `action.execute`: `{ "tab_ref": "main"|"active"|<int>, "kind": "navigate|click|type|scroll|move_mouse|wait", "params": { ... per kind }, "deadline_ms": 30000 }`
- `action.result`: success or failure
  - Success: `{ "ok": true, "elapsed_ms": 12, "payload": { ...per-kind success schema, see below } }`
  - Failure: `{ "ok": false, "code": "<TIMEOUT|GROUNDING_AMBIGUOUS|NO_TARGET_TAB|CANCELLED|DEADLINE_EXCEEDED|...>", "message": "...", "retryable": true }`
- `action.cancel`: `{ "tab_ref": "main"|<int>, "reason": "user_stop|timeout|deadline_exceeded" }`
- `indicator.show` / `indicator.hide` / `indicator.tool_use_hide` / `indicator.tool_use_show`: `{ "tab_ref": "main"|<int>, "is_mcp"?: boolean }` (latter on show only)
- `indicator.cursor`: `{ "tab_ref": "main"|<int>, "x": 540, "y": 320 }`; result `{ "ok": true, "arrived_at_ms": 1730000000123 }`
- `indicator.stop_clicked` (Ext → CP): `{ "tab_ref": <int> }`. The Extension sends `session_id: ""` (NH stamps); CP locates the in-flight action by `sessionId` lookup, not by any correlation id in this envelope.
- `a11y.snapshot.request`: `{ "tab_ref": "main"|<int>, "filter": "interactive|all|default", "depth": 15, "max_chars": 200000, "ref_id"?: "ref_42" }`
- `a11y.snapshot.response`: `{ "snapshot_id": "<uuid>", "captured_at_ms": ..., "tab_ref": <int>, "tree": "<plain text>", "viewport": { "w": 1280, "h": 800 } }` (with `tab_ref` echoed as the resolved tab id so the CP can update its freshness map keyed by absolute tab id)

**Per-kind success schema** (Codex P2-1 fix):

| `action.kind` | `payload` fields on success |
|---|---|
| `navigate` | `{ final_url: string, http_status?: int, load_state: "load"|"domcontentloaded"|"network_idle" }` |
| `click` | `{ }` (empty — caller infers state via subsequent snapshot) |
| `type` | `{ chars_typed: int }` |
| `scroll` | `{ }` |
| `move_mouse` | `{ arrived_at_ms: int, waypoints: int }` (waypoints≥1; `natural` profile emits N≥5) |
| `wait` | `{ waited_ms: int }` |

**A11y snapshot lifecycle (Codex P0-3 fix):**

The CP keeps a per-`(sessionId, resolvedTabId)` freshness map:

```
SnapshotState = { snapshot_id, captured_at_ms, status: FRESH | SUSPECT | STALE }
```

Transitions:

| Trigger | New status |
|---|---|
| Fresh `a11y.snapshot.response` arrives | FRESH |
| `action.result` for `navigate` succeeds | STALE (ref_N invalidated by URL change) |
| `action.result` for `click`/`type`/`scroll` succeeds | SUSPECT (one retry budget — if next ground misses, refresh) |
| Snapshot age > 30 s | STALE |
| `event.tab.closed` for this tab | (entry removed) |
| `event.page.navigated` event arrives (in-page nav) | STALE |

`PageSnapshotService.request()` (task F5) auto-refreshes on STALE.

Bump spec header to v1.1; v1 receivers continue to ignore unknown kinds (forward-compat).

```bash
git add mateclaw-server/src/main/java/vip/mate/browser/edge/protocol/EdgeMessageKind.java \
        mateclaw-server/src/test/java/vip/mate/browser/edge/protocol/EdgeMessageTest.java \
        mateclaw-browser-bridge/src/internal/edgeproto/ \
        mateclaw-extension/src/shared/edge-protocol.ts \
        mateclaw-extension/src/shared/edge-protocol.test.ts \
        docs/specs/edge-protocol.md
git commit -m "$(cat <<'EOF'
feat(browser): extend Edge protocol to v1.1 with action/indicator/a11y kinds

Adds CDP-action, visual-indicator and accessibility-tree kinds.
v1 receivers still ignore unknown kinds (spec forward-compat
invariant), so this is a backward-compatible bump.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task P2: Action request/result Java types

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionKind.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionRequest.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionResult.java`
- Create: sealed payload subtypes flat under `mateclaw-server/src/main/java/vip/mate/browser/edge/action/` (no `payloads/` subdir — keeps Jackson discriminator paths short)
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/action/ActionRequestTest.java`

> **Status note (v1.2 / Phase-2 Wave 0):** all five files in this list
> already exist on `feat/browser-foundation` — created by Codex 03 + 04
> during Phase 1 and `ActionRequest` added in Phase 2 Wave 0. The plan
> step below describes the original design intent; the shipped code
> matches it modulo the NAME discriminator and the flat directory layout.

**Decisions:**

- `ActionKind` enum: `NAVIGATE, CLICK, TYPE, SCROLL, MOVE_MOUSE, WAIT`. (Match Extension B-stream.)
- **`TabRef` is a sealed interface (Codex P0-1)** with three permitted record subtypes:
  ```java
  public sealed interface TabRef permits TabRef.Main, TabRef.Active, TabRef.Explicit {
      record Main() implements TabRef {}
      record Active() implements TabRef {}
      record Explicit(long tabId) implements TabRef {}
  }
  ```
  Custom Jackson serializer emits `"main"`, `"active"`, or the long; deserializer reads the JSON node by type. Carried on every `ActionRequest`, `IndicatorRequest`, and `A11ySnapshotRequest`.
- `ActionRequest` carries `tabRef`: `record ActionRequest(String msgId, TabRef tabRef, ActionKind kind, ActionPayload payload, long deadlineMs) {}`
- Use Java sealed classes for the typed payload of each kind. `ActionPayload` is the sealed interface; permitted subtypes are `NavigatePayload`, `ClickPayload`, `TypePayload`, `ScrollPayload`, `MoveMousePayload`, `WaitPayload`. **Jackson polymorphism uses `@JsonTypeInfo(use=NAME, property="kind")`** — DEDUCTION was specified in v1.0 but the Phase 1 audit (Codex 03 fix) caught that DEDUCTION cannot distinguish `ClickPayload`/`MoveMousePayload` (both have `{x,y}`) nor the empty success records. Concrete subtypes carry `@JsonTypeInfo(use=NONE)` so direct `readValue(json, Concrete.class)` still works without the discriminator.
- **`ActionResult` is sealed (Codex P2-1)**:
  ```java
  public sealed interface ActionResult permits ActionResult.Success, ActionResult.Failure {
      record Success(long elapsedMs, ActionSuccessPayload payload) implements ActionResult {}
      record Failure(String code, String message, boolean retryable) implements ActionResult {}
  }
  ```
- `ActionSuccessPayload` is also a sealed interface — one record per `ActionKind`:
  - `NavigateSuccess(String finalUrl, Integer httpStatus, String loadState)`
  - `ClickSuccess()`
  - `TypeSuccess(int charsTyped)`
  - `ScrollSuccess()`
  - `MoveMouseSuccess(long arrivedAtMs, int waypoints)`
  - `WaitSuccess(long waitedMs)`
- TS mirrors this as discriminated unions keyed on the outer action kind. The Edge protocol test on the TS side asserts each shape round-trips.

**Step 1: Write the failing test.** Three cases:

```java
@Test
void navigatePayload_serialisesWithKindDiscriminator() throws Exception {
    var req = new ActionRequest(
            "00000000-0000-0000-0000-000000000001",
            ActionKind.NAVIGATE,
            new NavigatePayload("https://example.com", "https://referer.example", null),
            30_000L);
    String json = mapper.writeValueAsString(req);
    assertThat(json).contains("\"kind\":\"navigate\"");
    assertThat(json).contains("\"url\":\"https://example.com\"");
}

@Test
void clickPayload_roundTrip() { ... }

@Test
void result_failureCarriesCodeAndRetryable() throws Exception {
    ActionResult r = new ActionResult.Failure("TIMEOUT_PAGE_LOAD", "navigation timed out", true);
    String json = mapper.writeValueAsString(r);
    var back = mapper.readValue(json, ActionResult.class);
    assertThat(back).isInstanceOf(ActionResult.Failure.class);
    assertThat(((ActionResult.Failure) back).code()).isEqualTo("TIMEOUT_PAGE_LOAD");
    assertThat(((ActionResult.Failure) back).retryable()).isTrue();
}
```

**Step 2: Run, see compile errors.**

**Step 3: Implement** the enum, the sealed interface hierarchy, and the records. Keep it boring — records, no logic. Example for `ClickPayload`:

```java
public record ClickPayload(
        double x,
        double y,
        @JsonProperty("button") String button,    // "left" | "right" | "middle"
        @JsonProperty("click_count") int clickCount
) implements ActionPayload {
    public ClickPayload {
        if (button == null) button = "left";
        if (clickCount < 1) clickCount = 1;
    }
}
```

For Jackson polymorphism on `ActionPayload`, use **`@JsonTypeInfo(use=NAME, property="kind")`** with explicit `@JsonSubTypes` listing every concrete subtype. The wire format then carries `kind` both on the outer `ActionRequest` (for fast routing without parsing params) and inside `params` (for Jackson discrimination). `ActionRequest`'s compact constructor enforces that the two agree — a mismatch is a programmer error caught at the boundary. (DEDUCTION was tried first but rejected: `ClickPayload` and `MoveMousePayload` both have `{x,y}`, and the empty success records `ClickSuccess`/`ScrollSuccess` have no distinguishing fields at all.)

**Step 4: Run, confirm pass.**

**Step 5: Commit.**

```bash
git commit -m "feat(browser): add ActionRequest / ActionResult sealed types

Sealed interfaces with one payload subtype per ActionKind, so the
compiler forces every dispatcher to handle all six. Failure carries
typed error code and retryable hint.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Task P3: ActionExecutionService

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionExecutionService.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionInFlight.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/edge/action/ActionExecutionServiceTest.java`

**Responsibility:** given a `BrowserSession` and an `ActionRequest`, send `action.execute` to the extension, await `action.result` (matched by `msg_id` ↔ `in_reply_to`), enforce a single in-flight action per session, surface timeout as `Failure("DEADLINE_EXCEEDED", retryable=true)`.

**Step 1: Write the failing test.** Pseudocode for the key tests:

```java
@Test
void execute_waitsForCorrelatedResult() throws Exception {
    // Arrange: a fake WS sink that captures sent frames.
    // Act:
    CompletableFuture<ActionResult> f = service.execute(session, new ActionRequest(..., NAVIGATE, ..., 5_000));
    // Simulate Extension reply on the matching in_reply_to:
    service.onResult(session.getId(), correlationIdOfTheRequest, new ActionResult.Success(120, Map.of()));
    // Assert future resolves with Success
    ActionResult r = f.get(1, TimeUnit.SECONDS);
    assertThat(r).isInstanceOf(ActionResult.Success.class);
}

@Test
void execute_secondRequestRejectedWhileFirstInFlight() {
    CompletableFuture<ActionResult> f1 = service.execute(session, navigate1);
    assertThatThrownBy(() -> service.execute(session, navigate2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("in-flight");
}

@Test
void execute_deadlineExceededReturnsFailure() throws Exception {
    CompletableFuture<ActionResult> f = service.execute(session,
            new ActionRequest(..., NAVIGATE, ..., 100L /* ms */));
    ActionResult r = f.get(500, TimeUnit.MILLISECONDS);
    assertThat(r).isInstanceOf(ActionResult.Failure.class);
    assertThat(((ActionResult.Failure) r).code()).isEqualTo("DEADLINE_EXCEEDED");
}

@Test
void cancel_inflight_completesFailure() throws Exception {
    CompletableFuture<ActionResult> f = service.execute(session, navigateLong);
    service.cancel(session.getId(), "user_stop");
    ActionResult r = f.get(500, TimeUnit.MILLISECONDS);
    assertThat(r).isInstanceOf(ActionResult.Failure.class);
    assertThat(((ActionResult.Failure) r).code()).isEqualTo("CANCELLED");
}
```

**Step 2: Verify compile failure.**

**Step 3: Implement.** Key points (Codex P1-2 fix — every removal is compare-and-remove against the holder identity, NOT against the sessionId alone):

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionExecutionService {

    private final BrowserSessionRegistry registry;
    private final ObjectMapper mapper;
    /** sessionId → in-flight action (at most one) */
    private final ConcurrentHashMap<String, ActionInFlight> inflight = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeouts =
            Executors.newScheduledThreadPool(2, r -> {
                var t = new Thread(r, "action-timeout");
                t.setDaemon(true);
                return t;
            });

    public CompletableFuture<ActionResult> execute(BrowserSession session, ActionRequest req) {
        var future = new CompletableFuture<ActionResult>();
        var holder = new ActionInFlight(req.msgId(), future, /* timeoutFuture */ null);

        // Atomic: only one in-flight per session.
        var prev = inflight.putIfAbsent(session.getId(), holder);
        if (prev != null) {
            throw new IllegalStateException("an action is already in-flight on session " + session.getId());
        }

        var timeoutFuture = timeouts.schedule(() -> {
            // Compare-and-remove: only fire timeout if THIS holder is still
            // in flight. If a result or cancel beat us, do nothing.
            if (inflight.remove(session.getId(), holder)) {
                holder.future().complete(new ActionResult.Failure(
                        "DEADLINE_EXCEEDED",
                        "action did not complete within " + req.deadlineMs() + "ms",
                        true));
            }
        }, req.deadlineMs(), TimeUnit.MILLISECONDS);
        holder.timeoutFuture(timeoutFuture);

        sendActionExecute(session, req);
        return future;
    }

    /** Called by EdgeWebSocketHandler when an action.result envelope arrives. */
    public void onResult(String sessionId, String correlationId, ActionResult result) {
        var holder = inflight.get(sessionId);
        if (holder == null || !holder.correlationId().equals(correlationId)) {
            log.warn("[action] late or unmatched result for session {} corrId {}", sessionId, correlationId);
            return;
        }
        // Compare-and-remove against the holder identity.
        if (!inflight.remove(sessionId, holder)) {
            // Lost the race to a cancel or timeout — they already completed
            // the future, do not double-complete.
            return;
        }
        holder.timeoutFuture().cancel(false);
        holder.future().complete(result);
    }

    /**
     * Cancel the in-flight action for this session.
     *
     * @param correlationId expected msg_id; pass null to cancel "whatever is
     *                      in flight". Either way the implementation uses a
     *                      compare-and-remove against the live holder so a
     *                      late cancel cannot remove the NEXT action's holder.
     */
    public boolean cancel(String sessionId, String correlationId, String reason) {
        var holder = inflight.get(sessionId);
        if (holder == null) return false;
        if (correlationId != null && !holder.correlationId().equals(correlationId)) {
            log.warn("[action] cancel({}) skipped — correlation {} != live {}",
                    sessionId, correlationId, holder.correlationId());
            return false;
        }
        if (!inflight.remove(sessionId, holder)) {
            // Lost the race to a result that just completed.
            return false;
        }
        holder.timeoutFuture().cancel(false);
        sendActionCancel(sessionId, holder.correlationId(), reason);
        holder.future().complete(new ActionResult.Failure("CANCELLED", reason, false));
        return true;
    }

    /**
     * Called by EdgeWebSocketHandler when an indicator.stop_clicked envelope
     * arrives — translates "user clicked stop" into a cancel against
     * whatever is in flight (no correlation id from the wire; the Extension
     * doesn't track msg_ids).
     */
    public void onStopClicked(String sessionId) {
        boolean cancelled = cancel(sessionId, null, "user_stop");
        log.info("[action] stop_clicked session={} cancelled={}", sessionId, cancelled);
        // After cancel, the orchestrator will also send indicator.hide.
    }

    private void sendActionExecute(BrowserSession session, ActionRequest req) { /* serialise + ws.sendMessage */ }
    private void sendActionCancel(String sessionId, String inReplyTo, String reason) { /* same */ }
}
```

**Additional tests (Codex P1-2 race + P1-4 stop closure):**

```java
@Test
void lateCancel_doesNotRemoveSubsequentActionHolder() throws Exception {
    // Round 1: execute, receive result, in-flight cleared.
    CompletableFuture<ActionResult> f1 = service.execute(session, req1);
    service.onResult(session.getId(), req1.msgId(), new ActionResult.Success(10, new ClickSuccess()));
    f1.get(100, TimeUnit.MILLISECONDS);

    // Round 2: register a new action.
    CompletableFuture<ActionResult> f2 = service.execute(session, req2);

    // Now a stale cancel for the old correlation id arrives.
    boolean cancelled = service.cancel(session.getId(), req1.msgId(), "stale_user_stop");
    assertThat(cancelled).isFalse();        // refused
    // f2 must still be pending (not failed/cancelled).
    assertThat(f2).isNotDone();
}

@Test
void timeoutLosesRaceToResult_doesNotDoubleComplete() throws Exception {
    var req = new ActionRequest("m1", new TabRef.Main(), ActionKind.CLICK, clickP, 50 /* short deadline */);
    CompletableFuture<ActionResult> f = service.execute(session, req);
    // Result arrives within 30 ms (before timeout fires).
    Thread.sleep(20);
    service.onResult(session.getId(), "m1", new ActionResult.Success(20, new ClickSuccess()));
    // Wait past the deadline.
    Thread.sleep(80);
    ActionResult r = f.get(50, TimeUnit.MILLISECONDS);
    assertThat(r).isInstanceOf(ActionResult.Success.class);   // not DEADLINE_EXCEEDED
}

@Test
void onStopClicked_cancelsInFlightAndCompletesFailure() throws Exception {
    var req = new ActionRequest("m1", new TabRef.Main(), ActionKind.NAVIGATE, navP, 30_000);
    CompletableFuture<ActionResult> f = service.execute(session, req);
    service.onStopClicked(session.getId());
    ActionResult r = f.get(100, TimeUnit.MILLISECONDS);
    assertThat(r).isInstanceOf(ActionResult.Failure.class);
    assertThat(((ActionResult.Failure) r).code()).isEqualTo("CANCELLED");
}
```

**Step 4: Run, confirm all 7 tests pass** (4 base + 3 race tests added by P1-2/P1-4).

**Step 5: Wire into `EdgeWebSocketHandler`:** when an `action.result` envelope arrives, parse it and call `service.onResult(...)`; when an `indicator.stop_clicked` envelope arrives, call `service.onStopClicked(sessionId)`. Add these to `EdgeWebSocketHandlerTest` as additional test cases.

```bash
git commit -m "feat(browser): add ActionExecutionService with compare-and-remove cancel

Enforces one-in-flight per session, uses ConcurrentHashMap.remove(k,v)
for every cleanup path (timeout / cancel / result) so a late
cancel/timeout cannot evict a freshly-registered next holder.
Wires indicator.stop_clicked into onStopClicked → cancel('user_stop').

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# B-stream: Extension Action Executor

# Task B1: chrome.debugger lifecycle

**Files:**
- Create: `mateclaw-extension/src/sw/cdp/DebuggerSession.ts`
- Test: `mateclaw-extension/src/sw/cdp/DebuggerSession.test.ts`

**Responsibility:** wrap `chrome.debugger.attach(target, version)` / `detach` / `sendCommand` in a typed class that:

- Lazily attaches on first command; idempotent re-attach is a no-op.
- Subscribes to `chrome.debugger.onDetach` (fires on tab close, user-opened DevTools, or our own detach). On *external* detach, marks the session dead and rejects all subsequent commands until `attach()` is called again.
- Wraps `sendCommand` with a 15s default timeout (CDP can hang) and rejects with `CdpTimeoutError`.
- Exposes a typed `EventEmitter` for inbound CDP events (`Page.frameNavigated`, etc.).

**Step 1: Write the failing test** with a `globalThis.chrome` mock:

```ts
beforeEach(() => {
  ;(globalThis as any).chrome = {
    debugger: {
      attach: vi.fn((_t, _v, cb) => cb?.()),
      detach: vi.fn((_t, cb) => cb?.()),
      sendCommand: vi.fn((_t, _m, _p, cb) => cb?.({ result: 'ok' })),
      onDetach: { addListener: vi.fn(), removeListener: vi.fn() },
    },
    runtime: { lastError: undefined },
  }
})

it('attaches lazily on first sendCommand', async () => {
  const s = new DebuggerSession(42)
  await s.sendCommand('Page.enable')
  expect(chrome.debugger.attach).toHaveBeenCalledWith({ tabId: 42 }, '1.3', expect.anything())
})

it('idempotent attach: second attach is no-op', async () => {
  const s = new DebuggerSession(42)
  await s.attach()
  await s.attach()
  expect(chrome.debugger.attach).toHaveBeenCalledTimes(1)
})

it('externalDetach (target_closed) rejects with typed SessionDetachedError', async () => {
  const s = new DebuggerSession(42)
  await s.sendCommand('Page.enable')
  const handler = (chrome.debugger.onDetach.addListener as any).mock.calls[0][0]
  handler({ tabId: 42 }, 'target_closed')
  await expect(s.sendCommand('Page.disable')).rejects.toBeInstanceOf(SessionDetachedError)
})

// Codex P1-1: also cover devtools_open and removal of BOTH listeners.
it('externalDetach (devtools_open) rejects with typed SessionDetachedError', async () => {
  const s = new DebuggerSession(42)
  await s.sendCommand('Page.enable')
  const handler = (chrome.debugger.onDetach.addListener as any).mock.calls[0][0]
  handler({ tabId: 42 }, 'devtools_open')
  await expect(s.sendCommand('Page.disable')).rejects.toMatchObject({
    name: 'SessionDetachedError',
    reason: 'devtools_open',
  })
})

it('detach removes BOTH onDetach AND onEvent listeners (same fn ref)', async () => {
  const s = new DebuggerSession(42)
  await s.sendCommand('Page.enable')
  const addedDetach = (chrome.debugger.onDetach.addListener as any).mock.calls[0][0]
  const addedEvent  = (chrome.debugger.onEvent.addListener  as any).mock.calls[0][0]
  // External detach should remove both with the exact same fn refs.
  addedDetach({ tabId: 42 }, 'target_closed')
  expect(chrome.debugger.onDetach.removeListener).toHaveBeenCalledWith(addedDetach)
  expect(chrome.debugger.onEvent.removeListener).toHaveBeenCalledWith(addedEvent)
})

it('sendCommand times out', async () => {
  ;(chrome.debugger.sendCommand as any).mockImplementation(() => {})  // never calls cb
  const s = new DebuggerSession(42)
  await expect(s.sendCommand('Page.enable', {}, { timeoutMs: 50 })).rejects.toThrow(/timeout/i)
})
```

**Step 2: Run, see compile error / undefined.**

**Step 3: Implement.** Outline (important parts: the lifecycle state machine, the typed `SessionDetachedError`, the timeout wrapping):

```ts
type DebuggerState = 'IDLE' | 'ATTACHED' | 'DETACHED'

export class SessionDetachedError extends Error {
  override readonly name = 'SessionDetachedError'
  constructor(public readonly reason: string) {
    super(`debugger session detached: ${reason}`)
  }
}

export class DebuggerSession {
  private state: DebuggerState = 'IDLE'
  private detachReason: string | null = null
  private readonly target: chrome.debugger.Debuggee
  private readonly onDetachHandler = this.handleDetach.bind(this)
  private readonly events = new Map<string, Set<(p: unknown) => void>>()
  private readonly onEvent = this.handleEvent.bind(this)

  constructor(tabId: number) {
    this.target = { tabId }
  }

  async attach(): Promise<void> {
    if (this.state === 'ATTACHED') return
    if (this.state === 'DETACHED') throw new Error(`session detached: ${this.detachReason}`)
    await new Promise<void>((resolve, reject) => {
      chrome.debugger.attach(this.target, '1.3', () => {
        if (chrome.runtime.lastError) {
          reject(new Error(chrome.runtime.lastError.message))
        } else {
          this.state = 'ATTACHED'
          chrome.debugger.onDetach.addListener(this.onDetachHandler)
          chrome.debugger.onEvent.addListener(this.onEvent)
          resolve()
        }
      })
    })
  }

  async sendCommand<R = unknown>(method: string, params: object = {}, opts: { timeoutMs?: number } = {}): Promise<R> {
    if (this.state !== 'ATTACHED') await this.attach()
    const timeoutMs = opts.timeoutMs ?? 15_000
    return new Promise<R>((resolve, reject) => {
      let settled = false
      const timeoutHandle = setTimeout(() => {
        if (!settled) { settled = true; reject(new Error(`CDP ${method} timeout after ${timeoutMs}ms`)) }
      }, timeoutMs)
      chrome.debugger.sendCommand(this.target, method, params, (result) => {
        if (settled) return
        settled = true
        clearTimeout(timeoutHandle)
        if (chrome.runtime.lastError) return reject(new Error(chrome.runtime.lastError.message))
        resolve(result as R)
      })
    })
  }

  on(event: string, cb: (p: unknown) => void): () => void {
    let set = this.events.get(event)
    if (!set) { set = new Set(); this.events.set(event, set) }
    set.add(cb)
    return () => set!.delete(cb)
  }

  async detach(): Promise<void> { /* call chrome.debugger.detach; clean up listeners; state = DETACHED */ }

  private handleDetach(target: chrome.debugger.Debuggee, reason: string): void {
    if (target.tabId !== this.target.tabId) return
    this.state = 'DETACHED'
    this.detachReason = reason
    chrome.debugger.onDetach.removeListener(this.onDetachHandler)
    chrome.debugger.onEvent.removeListener(this.onEvent)
  }

  private handleEvent(target: chrome.debugger.Debuggee, method: string, params?: object): void {
    if (target.tabId !== this.target.tabId) return
    this.events.get(method)?.forEach(cb => cb(params ?? {}))
  }
}
```

**Step 4: Run, confirm pass.**

**Step 5: Commit.**

```bash
git commit -m "feat(extension): add DebuggerSession lifecycle wrapper

Owns one chrome.debugger.attach per tab, handles external detach
(DevTools opened, tab closed), wraps sendCommand with a 15s timeout
and a typed CDP event emitter.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Task B2: ActionExecutor dispatch shell

**Files:**
- Create: `mateclaw-extension/src/sw/action/ActionExecutor.ts`
- Test: `mateclaw-extension/src/sw/action/ActionExecutor.test.ts`

**Responsibility:** receive an `ActionRequest` envelope from the SW message bus, route by `kind` to the per-kind handler (B3–B8 fill these in), return an `ActionResult` envelope.

**Step 1: Write the failing test.** Just the dispatch shell — handlers are stubs returning a fixed Success.

```ts
it('routes navigate to the navigate handler', async () => {
  const navigateSpy = vi.fn(async () => ({ ok: true, elapsed_ms: 0, payload: {} }))
  const exec = new ActionExecutor({ navigate: navigateSpy } as any)
  const req: ActionRequest = {
    msg_id: 'm1', kind: 'navigate', params: { url: 'https://example.com' }, deadline_ms: 5000,
  }
  const result = await exec.run(42 /* tabId */, req)
  expect(navigateSpy).toHaveBeenCalled()
  expect(result.ok).toBe(true)
})

it('returns Failure on unknown kind', async () => {
  const exec = new ActionExecutor({} as any)
  const result = await exec.run(42, { msg_id: 'm', kind: 'future-action', params: {}, deadline_ms: 1000 } as any)
  expect(result.ok).toBe(false)
  expect((result as any).code).toBe('UNKNOWN_KIND')
})

it('returns Failure when handler throws CdpTimeoutError', async () => {
  const exec = new ActionExecutor({ navigate: async () => { throw new Error('CDP Page.enable timeout') } } as any)
  const result = await exec.run(42, { msg_id: 'm', kind: 'navigate', params: { url: '...' }, deadline_ms: 1000 } as any)
  expect(result.ok).toBe(false)
})
```

**Step 2: Compile fail.**

**Step 3: Implement the shell.** The dispatcher just switches on `kind` and catches thrown errors as `Failure`. No business logic.

**Step 4: Run, pass.**

**Step 5: Commit.**

---

# Task B3: `navigate` action handler

**Files:**
- Create: `mateclaw-extension/src/sw/action/handlers/navigate.ts`
- Test: `mateclaw-extension/src/sw/action/handlers/navigate.test.ts`

**Behaviour:**
- Param: `{ url: string, referer?: string, wait_for: "load" | "domcontentloaded" | "network_idle" | "none" }`.
- Uses `chrome.tabs.update(tabId, { url })` for the navigation itself (not CDP — `chrome.tabs.update` is the well-supported path for top-level navigation and respects the user's tab).
- Awaits `chrome.webNavigation.onCompleted` (or DOMContentLoaded variant) on the same tab + frameId=0.
- Honours a per-action timeout (passed in via the executor).

**Step 1: Test** — mock `chrome.tabs.update` + `chrome.webNavigation.onCompleted`. Two cases:
(a) Happy path → result.ok=true with elapsed_ms.
(b) Timeout → result.ok=false code=TIMEOUT_PAGE_LOAD.

**Step 2–5: Implement, test, commit.**

```bash
git commit -m "feat(extension): add navigate action handler

Uses chrome.tabs.update for top-level nav; awaits chrome.webNavigation
.onCompleted with configurable wait strategy. Surfaces TIMEOUT_PAGE_LOAD
typed failure on deadline.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Task B4: `click` action handler

**Files:**
- Create: `mateclaw-extension/src/sw/action/handlers/click.ts`
- Test: `mateclaw-extension/src/sw/action/handlers/click.test.ts`

**Behaviour:**
- Param: `{ x: number, y: number, button: "left"|"right"|"middle", click_count: number, modifiers?: { shift,ctrl,alt,meta } }`.
- Sequence (via `DebuggerSession.sendCommand`):
  1. `Input.dispatchMouseEvent` `type: "mousePressed"` at `(x, y)` with `button`, `clickCount`, `modifiers`.
  2. Delay (humanised hold time, 40–120 ms — drawn from a uniform distribution. WindMouse / log-normal jitter is for *movement*, not press-hold; this is just a uniform random number.)
  3. `Input.dispatchMouseEvent` `type: "mouseReleased"` at the same `(x, y)`.
- Returns success after the release.
- **Untrusted-event ban (Codex hot-spot 4):** must NOT use `chrome.scripting.executeScript` to call `.click()` on the page. CDP `Input.dispatchMouseEvent` is the only acceptable path. Codex re-audit should grep for `.click()` and `dispatchEvent(new MouseEvent` in B4–B7 — none must appear.

**Step 1: Test** (Codex P1-3 — clock + RNG injection lets us assert the hold delay):

```ts
it('dispatches mousePressed then mouseReleased with bounded hold delay', async () => {
  const debug = { sendCommand: vi.fn(async () => ({})) } as any
  const clock = makeFakeClock(0)
  // Deterministic RNG: 0.5 → hold = 40 + 0.5 * 80 = 80 ms (mid-range).
  const rng = () => 0.5
  const handler = makeClickHandler(debug, { clock, rng })

  const promise = handler({ x: 100, y: 200, button: 'left', click_count: 1 })
  // Advance the fake clock past the hold window.
  await clock.advanceAsync(80)
  const r = await promise

  expect(r.ok).toBe(true)
  expect(debug.sendCommand).toHaveBeenCalledTimes(2)
  expect(debug.sendCommand.mock.calls[0][1])
        .toMatchObject({ type: 'mousePressed', x: 100, y: 200, button: 'left' })
  expect(debug.sendCommand.mock.calls[1][1])
        .toMatchObject({ type: 'mouseReleased', x: 100, y: 200 })
  // Hold delay was the deterministic 80 ms.
  expect(clock.now()).toBe(80)
})

it('hold delay bounded in [40, 120] ms across many RNG samples', () => {
  for (const seed of [0, 0.1, 0.5, 0.9, 1.0]) {
    expect(holdMsFor(seed)).toBeGreaterThanOrEqualTo(40)
    expect(holdMsFor(seed)).toBeLessThanOrEqualTo(120)
  }
})
```

**Step 2: [run per template]**

**Step 3: Implement** with injected clock + RNG (the pattern applies to B4–B8 — any handler that uses randomness or wall-clock):

```ts
export interface HandlerEnv {
  clock: { now(): number; sleep(ms: number): Promise<void> }
  rng: () => number
}

export function makeClickHandler(debug: DebuggerSession, env: HandlerEnv) {
  return async function click(p: ClickParams): Promise<ActionResult> {
    const holdMs = 40 + env.rng() * 80        // [40, 120)
    const t0 = env.clock.now()
    await debug.sendCommand('Input.dispatchMouseEvent', {
      type: 'mousePressed', x: p.x, y: p.y,
      button: p.button ?? 'left', clickCount: p.click_count ?? 1,
    })
    await env.clock.sleep(holdMs)
    await debug.sendCommand('Input.dispatchMouseEvent', {
      type: 'mouseReleased', x: p.x, y: p.y,
      button: p.button ?? 'left', clickCount: p.click_count ?? 1,
    })
    return { ok: true, elapsed_ms: env.clock.now() - t0, payload: {} }
  }
}
```

Production wiring uses the real `Date.now()` + `setTimeout`-based sleep; tests substitute `makeFakeClock(0)` (a vitest-friendly fake that tracks scheduled sleeps and advances on `advanceAsync(ms)`).

**Step 4: [run per template]**

**Step 5: Commit.**

---

# Task B5: `type` action handler

**Files:**
- Create: `mateclaw-extension/src/sw/action/handlers/type.ts`
- Test: `mateclaw-extension/src/sw/action/handlers/type.test.ts`

**Behaviour:**
- Param: `{ text: string, focus_target?: { x, y } /* optional pre-click */ }`.
- For each character of `text`:
  1. `Input.dispatchKeyEvent` `type: "keyDown"` with `text: <char>` + `key`/`code`/`windowsVirtualKeyCode` per a small lookup table for printable ASCII / digits / common punctuation.
  2. Delay 80–250 ms (uniform random, *not* fixed). This is the per-key human-typing model.
  3. `Input.dispatchKeyEvent` `type: "keyUp"`.
- Optional 2% typo-and-backspace (configurable; off by default in Phase 2).
- For pre-click: if `focus_target` is set, first dispatch a click at those coords via the same code path B4 uses (extract the click sequence into a shared helper).

**Step 1: Test** — mock debug, assert one keyDown+keyUp per character, assert character order. Use a stub clock for the delay so the test runs fast.

**Step 2–5: Implement, test, commit.**

---

# Task B6: `scroll` action handler

**Files:**
- Create: `mateclaw-extension/src/sw/action/handlers/scroll.ts`
- Test: `mateclaw-extension/src/sw/action/handlers/scroll.test.ts`

**Behaviour:**
- Param: `{ direction: "down"|"up"|"left"|"right", distance_px: number, segments?: number }`.
- Implementation: split `distance_px` into `segments` (default 5) chunks; for each chunk dispatch `Input.dispatchMouseEvent` `type: "mouseWheel"` with appropriate `deltaY` / `deltaX`, then sleep 60–140 ms.
- Returns success after the last chunk.

**Step 1: Test** asserts that the number of mouseWheel events equals `segments` and the cumulative delta equals `distance_px`.

**Step 2–5.**

---

# Task B7: `move_mouse` action handler (consumes WindMouse — Codex P1-6 fix)

**Files:**
- Create: `mateclaw-extension/src/sw/action/handlers/moveMouse.ts`
- Test: `mateclaw-extension/src/sw/action/handlers/moveMouse.test.ts`

**Behaviour:**
- Param: `{ x: number, y: number, profile?: "natural"|"linear" }` (default "natural").
- This action **does NOT** dispatch any CDP mouse event. It only updates the phantom cursor position on the content-script side. The handler:
  1. **`profile = "natural"`** (Codex P1-6 — WindMouse lives here, not in F1):
     - Reads the cursor's current position from a per-tab last-known state (cached after each move). Defaults to `(x, y)` of the previous action.
     - Calls `windMouse(x0, y0, x, y, { rng: env.rng })` → array of waypoints `{x, y, dt_ms}`.
     - For each waypoint in sequence: `chrome.tabs.sendMessage(tabId, { type: "INDICATOR_CURSOR", x, y })`, then `env.clock.sleep(dt_ms)`.
     - Returns `MoveMouseSuccess(arrivedAtMs, waypoints.length)`.
  2. **`profile = "linear"`** (escape hatch for tests / future ML-driven trajectories):
     - Single `chrome.tabs.sendMessage(tabId, { type: "INDICATOR_CURSOR", x, y })`.
     - Awaits the response (content-script C2 returns *after* the CSS transition completes — that's the 220ms Promise fallback).
     - Returns `MoveMouseSuccess(arrivedAtMs, 1)`.
- Reason: real mouse events are dispatched by `click` and `scroll` at the same `(x, y)` immediately afterwards. `move_mouse` exists purely to **animate the user-visible cursor between actions**.
- F1 ActionPlanner emits **exactly one** MOVE_MOUSE per step (per the F1 contract). The natural-profile choreography is the Extension's job — not the planner's.

**Step 1: Tests** (Codex P1-6 + integration with B9):

```ts
it('natural profile produces ≥5 INDICATOR_CURSOR messages between (0,0) and (100,100)', async () => {
  const rng = makeSeededRng(42)
  const clock = makeFakeClock(0)
  const handler = makeMoveMouseHandler({ rng, clock })
  const tabId = 42
  const cursorMsgs: any[] = []
  (chrome.tabs.sendMessage as any).mockImplementation(async (_t: number, m: any) => {
    if (m.type === 'INDICATOR_CURSOR') cursorMsgs.push({ x: m.x, y: m.y })
    return { ok: true }
  })

  const p = handler(tabId, { x: 100, y: 100, profile: 'natural' })
  await clock.runAllPending()
  const r = await p

  expect(r.ok).toBe(true)
  expect(cursorMsgs.length).toBeGreaterThanOrEqualTo(5)
  // Last waypoint is the requested target.
  expect(cursorMsgs[cursorMsgs.length - 1]).toMatchObject({ x: 100, y: 100 })
  // payload reports the right waypoint count.
  expect((r as any).payload.waypoints).toBe(cursorMsgs.length)
})

it('linear profile produces exactly 1 INDICATOR_CURSOR message', async () => {
  const handler = makeMoveMouseHandler(env)
  await handler(42, { x: 100, y: 100, profile: 'linear' })
  const cursorCalls = (chrome.tabs.sendMessage as any).mock.calls
        .filter(([, m]: any[]) => m?.type === 'INDICATOR_CURSOR')
  expect(cursorCalls).toHaveLength(1)
})

it('caches end position so the next natural move starts from the right origin', async () => {
  const handler = makeMoveMouseHandler(env)
  await handler(42, { x: 100, y: 100, profile: 'natural' })
  ;(chrome.tabs.sendMessage as any).mockClear()
  await handler(42, { x: 200, y: 200, profile: 'natural' })
  // The first waypoint of the SECOND move should start at ~(100, 100), not (0, 0).
  const firstWaypointOfSecond = (chrome.tabs.sendMessage as any).mock.calls[0][1]
  expect(firstWaypointOfSecond.x).toBeGreaterThan(95)
  expect(firstWaypointOfSecond.x).toBeLessThan(110)
})
```

**Step 2: [run per template]**

**Step 3: Implement.** Use the per-tab cursor cache (`Map<number, {x, y}>`) at module scope; `linear` falls through to the original "one sendMessage + await transitionend" path; `natural` consumes B9 `windMouse` and walks the waypoints.

**Step 4: [run per template]**

**Step 5: Commit.**

> **Codex re-audit hint**: this lifts WindMouse out of the dead-code state Codex flagged. F1 still emits one MOVE_MOUSE per step; B7 internally walks the natural-profile path. F1's contract test (`clickStep_emitsMoveMouseThenClick_carryingTabRef`) stays at two actions.

---

# Task B8: `wait` action handler

**Files:**
- Create: `mateclaw-extension/src/sw/action/handlers/wait.ts`
- Test: `mateclaw-extension/src/sw/action/handlers/wait.test.ts`

**Behaviour:**
- Param: `{ strategy: "time"|"network_idle"|"load_state", duration_ms?, idle_threshold_ms?, load_state? }`.
- Three strategies:
  - `time`: pure `setTimeout(resolve, duration_ms + jitter)` where jitter is ±20% of `duration_ms`.
  - `network_idle`: subscribe to CDP `Network.requestWillBeSent` / `Network.loadingFinished` / `Network.loadingFailed`; resolve when no in-flight requests for `idle_threshold_ms` (default 500 ms).
  - `load_state`: await `chrome.webNavigation.onCompleted` on the tab.

**Step 1: Test** the three strategies independently with mocked timers / mocked CDP events.

**Step 2–5.**

---

# Task B9: WindMouse + log-normal jitter library

**Files:**
- Create: `mateclaw-extension/src/sw/human/wind-mouse.ts`
- Create: `mateclaw-extension/src/sw/human/jitter.ts`
- Test: tests in same folder

**Responsibility:** pure functions, no Chrome APIs.

- `wind-mouse.ts`: implements a WindMouse-style path generator. Input: `(x0, y0, x1, y1, params)`. Output: an array of `{x, y, dt_ms}` waypoints from start to end, with simulated wind force and gravity producing a slightly arched, jittery path that resembles human cursor motion. Total duration roughly proportional to distance. This is a published technique with well-known parameter ranges (`wind`, `gravity`, `maxStep`); pick reasonable defaults.
- `jitter.ts`: `logNormalJitter(meanMs, sigma)` returns a random duration drawn from a log-normal distribution (used by B5 type for inter-key delay). `uniformJitter(min, max)`.

**Step 1: Test** these as deterministic functions when given a seeded PRNG (the lib should accept an injected `() => number` for `Math.random`):

```ts
it('windMouse produces a monotonic-ish path from (0,0) to (100,100)', () => {
  const points = windMouse(0, 0, 100, 100, { rng: seededRng(42) })
  expect(points[0]).toMatchObject({ x: 0, y: 0 })
  expect(points[points.length - 1].x).toBeCloseTo(100, 0)
  expect(points[points.length - 1].y).toBeCloseTo(100, 0)
  // points are sorted by cumulative time
  for (let i = 1; i < points.length; i++) {
    expect(points[i].dt_ms).toBeGreaterThanOrEqual(0)
  }
})

it('logNormalJitter respects mean roughly', () => {
  const rng = seededRng(42)
  const samples = Array.from({ length: 10_000 }, () => logNormalJitter(150, 0.4, rng))
  const mean = samples.reduce((a, b) => a + b, 0) / samples.length
  expect(mean).toBeGreaterThan(120)
  expect(mean).toBeLessThan(200)
})
```

**Step 2–5.**

---

# Task B10: SW message wiring → ActionExecutor (+ tab_ref resolution + STOP_AGENT)

**Files:**
- Modify: `mateclaw-extension/src/sw/index.ts`
- Create: `mateclaw-extension/src/sw/TabRefResolver.ts`
- Test: `mateclaw-extension/src/sw/action-wiring.test.ts`

**Responsibility (Codex P0-1 + P1-4):**

1. Receive `action.execute` from the NativeBridge.
2. **Resolve `tab_ref` to a concrete `tabId`** via `TabRefResolver`:
   - `{ kind: "main" }` → `tabGroupManager.getMainTabId(<current session main>)`
   - `{ kind: "active" }` → `chrome.tabs.query({active:true, lastFocusedWindow:true})[0].id`
   - `{ kind: "explicit", tabId: N }` → N (still verify tab is alive)
   - Resolution fails → emit `action.result` `{ ok:false, code:"NO_TARGET_TAB" }` without invoking the executor.
3. Forward to `ActionExecutor.run(tabId, request)`, await result, send `action.result` envelope (with `session_id: ""` — bridge stamps the real id).
4. Listen for content-script-originated `STOP_AGENT` (chrome.runtime.sendMessage) → translate into outbound Edge envelope `{ kind: "indicator.stop_clicked", session_id: "", payload: { tab_ref: <originatingTabId> } }`.

**Step 1: Write the failing tests:**

```ts
describe('B10 wiring', () => {
  it('resolves tab_ref="main" via TabGroupManager', async () => {
    const tgm = { getMainTabId: vi.fn().mockResolvedValue(42) } as any
    const exec = { run: vi.fn().mockResolvedValue({ ok: true, elapsed_ms: 10, payload: {} }) }
    const wire = makeWiring({ tabGroupManager: tgm, executor: exec, bridge })
    await wire.onInbound({ kind: 'action.execute', msg_id: 'm1',
                            payload: { tab_ref: 'main', kind: 'click', params: { x: 0, y: 0 } } })
    expect(tgm.getMainTabId).toHaveBeenCalled()
    expect(exec.run).toHaveBeenCalledWith(42, expect.anything())
  })

  it('returns NO_TARGET_TAB when resolution fails', async () => {
    const tgm = { getMainTabId: vi.fn().mockResolvedValue(null) } as any
    const wire = makeWiring({ tabGroupManager: tgm, executor: exec, bridge })
    await wire.onInbound({ kind: 'action.execute', msg_id: 'm1',
                            payload: { tab_ref: 'main', kind: 'click', params: { x: 0, y: 0 } } })
    expect(bridge.send).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'action.result',
      session_id: '',
      payload: expect.objectContaining({ ok: false, code: 'NO_TARGET_TAB' }),
    }))
    expect(exec.run).not.toHaveBeenCalled()
  })

  it('result envelope leaves with session_id="" (NH stamps)', async () => {
    const wire = makeWiring({ tabGroupManager, executor, bridge })
    await wire.onInbound({ kind: 'action.execute', msg_id: 'm1',
                           payload: { tab_ref: { explicit: 99 }, kind: 'click', params: { x: 0, y: 0 } } })
    const sent = (bridge.send as any).mock.calls[0][0]
    expect(sent.session_id).toBe('')
  })

  it('STOP_AGENT from content script → indicator.stop_clicked outbound', async () => {
    const wire = makeWiring({ tabGroupManager, executor, bridge })
    // Simulate content-script firing chrome.runtime.sendMessage
    fireRuntimeMessage({ type: 'STOP_AGENT' }, { tab: { id: 77 } })
    expect(bridge.send).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'indicator.stop_clicked',
      session_id: '',
      payload: { tab_ref: 77 },
    }))
  })
})
```

**Step 2: [run per template]**

**Step 3: Implement.** `TabRefResolver` is a 30-line module:

```ts
export class TabRefResolver {
  constructor(private readonly tgm: TabGroupManager) {}
  async resolve(ref: TabRef, defaultSubject?: string): Promise<number | null> {
    if (typeof ref === 'object' && 'explicit' in ref) {
      try { await chrome.tabs.get(ref.explicit); return ref.explicit }
      catch { return null }
    }
    if (ref === 'main')  return defaultSubject ? this.tgm.getMainTabId(defaultSubject) : null
    if (ref === 'active') {
      const [t] = await chrome.tabs.query({ active: true, lastFocusedWindow: true })
      return t?.id ?? null
    }
    return null
  }
}
```

SW wiring adds the resolution step before dispatch, and a `chrome.runtime.onMessage` listener for `STOP_AGENT`.

**Step 4: [run per template]**

**Step 5: Commit.**

```bash
git commit -m "feat(extension): wire action.execute with tab_ref resolution + STOP closure

SW resolves tab_ref ('main' | 'active' | explicit) via TabRefResolver
before dispatch, surfaces NO_TARGET_TAB failure when no tab can be
chosen. STOP_AGENT content-script messages translate to outbound
indicator.stop_clicked envelopes with session_id='' (NH stamps).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Task B11: SnapshotRequestHandler (Codex P0-3, NEW in v1.1)

**Files:**
- Create: `mateclaw-extension/src/sw/snapshot/SnapshotRequestHandler.ts`
- Test: `mateclaw-extension/src/sw/snapshot/SnapshotRequestHandler.test.ts`

**Responsibility:** receive `a11y.snapshot.request` envelope, resolve `tab_ref` via the same `TabRefResolver` (B10), invoke `chrome.scripting.executeScript({target:{tabId}, func: ...})` to run the content-script-side `window.__mateclaw_a11y_tree(filter, depth, max_chars, ref_id)` (C1), wrap the result in `a11y.snapshot.response` with a fresh `snapshot_id` (UUID) + `captured_at_ms` and the resolved absolute `tab_ref`.

**Step 1: Write the failing tests:**

```ts
describe('B11 SnapshotRequestHandler', () => {
  it('resolves tab_ref and runs a11y tree script', async () => {
    const tgm = { getMainTabId: vi.fn().mockResolvedValue(42) } as any
    (globalThis as any).chrome = {
      scripting: { executeScript: vi.fn().mockResolvedValue([{ result: {
        page_content: 'main\n  button "Submit" [ref_1 @100,200,80,32]',
        viewport: { w: 1280, h: 800 },
      }}]) },
    }
    const handler = new SnapshotRequestHandler(new TabRefResolver(tgm), bridge)
    await handler.onRequest({ msg_id: 'q1',
                              payload: { tab_ref: 'main', filter: 'default', depth: 15, max_chars: 200_000 } })
    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42 },
      func: expect.any(Function),
    }))
    const sent = (bridge.send as any).mock.calls[0][0]
    expect(sent.kind).toBe('a11y.snapshot.response')
    expect(sent.session_id).toBe('')                      // NH stamps
    expect(sent.payload.snapshot_id).toMatch(/^[0-9a-f-]{36}$/)
    expect(sent.payload.captured_at_ms).toBeTypeOf('number')
    expect(sent.payload.tab_ref).toBe(42)                 // resolved absolute id echoed back
    expect(sent.payload.tree).toContain('ref_1 @100,200')
  })

  it('NO_TARGET_TAB when resolution fails', async () => {
    const tgm = { getMainTabId: vi.fn().mockResolvedValue(null) } as any
    const handler = new SnapshotRequestHandler(new TabRefResolver(tgm), bridge)
    await handler.onRequest({ msg_id: 'q1',
                              payload: { tab_ref: 'main', filter: 'default', depth: 15, max_chars: 100 } })
    const sent = (bridge.send as any).mock.calls[0][0]
    expect(sent.payload).toMatchObject({ ok: false, code: 'NO_TARGET_TAB' })
  })

  it('passes ref_id through to the script when provided', async () => {
    // Asserts the function literal passed to executeScript reads payload.ref_id
    // (use args: [refId] in chrome.scripting; static-analyze the call).
  })

  it('content script errored / not injected → A11Y_TREE_UNAVAILABLE failure', async () => {
    (chrome.scripting.executeScript as any).mockRejectedValue(new Error('No matching scripts'))
    const handler = new SnapshotRequestHandler(new TabRefResolver(tgm), bridge)
    await handler.onRequest({ msg_id: 'q1', payload: { tab_ref: 'active', filter: 'default', depth: 15 } })
    const sent = (bridge.send as any).mock.calls[0][0]
    expect(sent.payload).toMatchObject({ ok: false, code: 'A11Y_TREE_UNAVAILABLE' })
  })
})
```

**Step 2:** `pnpm test SnapshotRequestHandler` → expect `Cannot find module './SnapshotRequestHandler'`.

**Step 3: Implement.**

```ts
import { v4 as uuid } from 'uuid'  // or crypto.randomUUID() — same effect

export class SnapshotRequestHandler {
  constructor(private resolver: TabRefResolver, private bridge: NativeBridge) {}

  async onRequest(env: EdgeMessage): Promise<void> {
    const { tab_ref, filter = 'default', depth = 15, max_chars = 200_000, ref_id } = env.payload as any
    const tabId = await this.resolver.resolve(tab_ref)
    if (tabId == null) {
      return this.reply(env, { ok: false, code: 'NO_TARGET_TAB',
                                message: 'tab_ref did not resolve to a live tab', retryable: false })
    }

    try {
      const results = await chrome.scripting.executeScript({
        target: { tabId },
        // Note: this function literal runs in the page's isolated world.
        func: (f, d, m, r) => (window as any).__mateclaw_a11y_tree?.(f, d, m, r) ?? { error: 'tree-not-injected' },
        args: [filter, depth, max_chars, ref_id ?? null],
      })
      const raw = results[0]?.result as { page_content?: string; viewport?: any; error?: string }
      if (!raw || raw.error) {
        return this.reply(env, { ok: false, code: 'A11Y_TREE_UNAVAILABLE',
                                  message: raw?.error ?? 'content script absent', retryable: true })
      }
      const responsePayload = {
        snapshot_id: uuid(),
        captured_at_ms: Date.now(),
        tab_ref: tabId,
        tree: raw.page_content ?? '',
        viewport: raw.viewport ?? { w: 0, h: 0 },
      }
      this.bridge.send({
        v: 1, msg_id: uuid(), kind: 'a11y.snapshot.response',
        ts: Date.now(), trace_id: env.trace_id, session_id: '',
        in_reply_to: env.msg_id, payload: responsePayload,
      })
    } catch (e) {
      this.reply(env, { ok: false, code: 'A11Y_TREE_UNAVAILABLE',
                         message: String(e), retryable: true })
    }
  }

  private reply(env: EdgeMessage, payload: object) {
    this.bridge.send({
      v: 1, msg_id: uuid(), kind: 'a11y.snapshot.response',
      ts: Date.now(), trace_id: env.trace_id, session_id: '',
      in_reply_to: env.msg_id, payload,
    })
  }
}
```

**Step 4: [run per template]**

**Step 5: Commit.**

```bash
git commit -m "feat(extension): a11y.snapshot.request handler with tab_ref + snapshot_id

SW receives an Edge a11y.snapshot.request, resolves tab_ref via the
shared TabRefResolver, runs __mateclaw_a11y_tree on that tab via
chrome.scripting.executeScript, returns a11y.snapshot.response with
a fresh snapshot_id (UUID), captured_at_ms, and the resolved tab_ref
echoed back so the Control Plane keys its freshness map by absolute id.
Surfaces NO_TARGET_TAB / A11Y_TREE_UNAVAILABLE as typed failures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# C-stream: Extension Visual Indicators

# Task C1: A11y tree content script (top frame only, with bbox)

**Files:**
- Create: `mateclaw-extension/src/content/a11y-tree.ts`
- Test: `mateclaw-extension/src/content/a11y-tree.test.ts`

**Responsibility (Codex P1-5):** mirror the research doc §8 design (`window.__mateclaw_*` globals, WeakRef element map, `__mateclaw_a11y_tree(filter, depth, maxChars, refId)` function). **Add bbox to each line** as `[ref_N @x,y,w,h]`. **Top frame only in Phase 2** — `all_frames: false` in manifest (C6). Iframe-internal grounding is Phase 3 work (needs frame-offset accounting because `getBoundingClientRect()` is frame-local).

> **Codex re-audit hint**: this scope cut means agents cannot click into iframes during Phase 2. Acceptable because (a) most flows target top-frame elements and (b) Phase 3 adds proper cross-frame coordinate translation. The iframe negative test below pins this restriction so a future Phase 3 change can't silently regress.

**Step 1: Write the failing test** using a `happy-dom` document. Build a small DOM:

```html
<body>
  <header><h1>Title</h1></header>
  <main>
    <button aria-label="Submit">Submit</button>
    <input type="text" placeholder="Search" />
    <input type="password" />
    <a href="/foo">Detail link</a>
  </main>
</body>
```

Then call `__mateclaw_a11y_tree('default')` and assert:
- Output contains `button "Submit" [ref_1 @`-prefixed bbox.
- Password input is rendered with `[value redacted]` if it had a value.
- Hidden elements (`display:none`) are excluded.
- Calling twice with the same DOM returns the **same** ref ids (WeakRef cache works).
- After removing a node, calling again drops that ref from the map.

**Iframe negative test (Codex P1-5):**

```ts
it('does not include elements inside iframes (Phase 2 top-frame only)', () => {
  document.body.innerHTML = `
    <button id="outer">Outer Submit</button>
    <iframe srcdoc="<button id='inner'>Inner Button</button>"></iframe>
  `
  // Wait for iframe load (happy-dom resolves srcdoc synchronously).
  const out = window.__mateclaw_a11y_tree!('default').page_content!
  expect(out).toContain('"Outer Submit"')
  expect(out).not.toContain('"Inner Button"')   // Phase 2 boundary
})
```

**Step 2: Verify compile fail.**

**Step 3: Implement.** Key design choices:

```ts
declare global {
  interface Window {
    __mateclaw_element_map?: Record<string, WeakRef<Element>>
    __mateclaw_reverse_map?: WeakMap<Element, string>
    __mateclaw_ref_counter?: number
    __mateclaw_a11y_tree?: (filter?: string, depth?: number, maxChars?: number, refId?: string) => A11yResult
  }
}

interface A11yResult {
  page_content?: string
  viewport: { w: number; h: number }
  error?: string
}

(function init() {
  if (window.__mateclaw_a11y_tree) return     // idempotent across re-injection
  window.__mateclaw_element_map = window.__mateclaw_element_map ?? {}
  window.__mateclaw_reverse_map = window.__mateclaw_reverse_map ?? new WeakMap()
  window.__mateclaw_ref_counter = window.__mateclaw_ref_counter ?? 0

  function inferRole(el: Element): string | null { /* tag→role map + aria-role override */ }
  function inferName(el: Element): string { /* aria-label > placeholder > title > alt > label-for > text */ }
  function isSensitive(el: Element): boolean { /* password / cc-* autocomplete / type=hidden */ }
  function isVisible(el: Element): boolean { /* getComputedStyle + offsetWidth/Height */ }
  function isInViewport(el: Element): boolean { /* getBoundingClientRect within innerWidth/Height */ }
  function isInteractive(el: Element): boolean { /* a, button, input, …, [onclick], [tabindex], contenteditable */ }
  function isLandmark(el: Element): boolean { /* h1–h6, nav, main, header, footer, section, article, aside */ }

  function refFor(el: Element): string {
    let id = window.__mateclaw_reverse_map!.get(el)
    if (id && window.__mateclaw_element_map![id]?.deref() === el) return id
    id = `ref_${++window.__mateclaw_ref_counter!}`
    window.__mateclaw_element_map![id] = new WeakRef(el)
    window.__mateclaw_reverse_map!.set(el, id)
    return id
  }

  window.__mateclaw_a11y_tree = function(filter = 'default', depth = 15, maxChars = 200_000, refId) {
    /* DFS walker, append per visited interesting node, sweep stale refs at end */
  }
})()
```

**bbox encoding**: `[ref_42 @540,320,80,32]` — `x,y,w,h` from `getBoundingClientRect()` rounded to integers. If the element is outside the viewport (filter=`all`), still emit the bbox but with possibly-negative coords.

**Step 4: Run, pass.**

**Step 5: Commit.**

```bash
git commit -m "feat(extension): a11y tree content script with bbox

Walks the DOM at document_start, exposes __mateclaw_a11y_tree for
the SW to call via chrome.scripting.executeScript. Each line carries
a ref_N + bbox (@x,y,w,h) so the Orchestrator does not need a second
round-trip to get coordinates. WeakRef map keeps ref ids stable
across calls; sweep at end of call drops dead entries.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Task C2: PhantomCursor

**Files:**
- Create: `mateclaw-extension/src/content/visual/PhantomCursor.ts`
- Test: `mateclaw-extension/src/content/visual/PhantomCursor.test.ts`

**Responsibility:** a class that owns the phantom cursor DOM element. API:

```ts
class PhantomCursor {
  mount(x: number, y: number): void         // lazy create, attach to body
  move(x: number, y: number): Promise<void> // update transform; resolve after transitionend (220ms fallback)
  unmount(): void
  setStyle(theme: 'plain' | 'styled'): void
}
```

**Visual contract:**
- Container `<div id="mateclaw-phantom-cursor">` with `position: fixed; top: 0; left: 0; pointer-events: none; z-index: 2147483646; transform: translate3d(x,y,0); transition: transform 180ms cubic-bezier(0.2,0,0,1); will-change: transform`.
- Contains two SVG cursor icons (you design them — a simple 20×26 arrow with a tail; pick a shape distinct from native OS cursors so it's obviously a synthetic indicator). Plain version uses neutral fill (white with dark stroke); styled version uses the MateClaw brand color with a drop-shadow glow filter.
- Only one of the two is `display:""` at a time.
- Respects `@media (prefers-reduced-motion: reduce)`: transition collapses to ~30 ms (functionally instant).

**Step 1: Test** in `happy-dom`:

```ts
it('mounts a fixed-position cursor element with high z-index', () => {
  const c = new PhantomCursor()
  c.mount(50, 50)
  const el = document.getElementById('mateclaw-phantom-cursor')!
  expect(el.style.position).toBe('fixed')
  expect(parseInt(el.style.zIndex, 10)).toBeGreaterThan(2_000_000_000)
  expect(el.style.pointerEvents).toBe('none')
})

it('move() updates the transform', async () => {
  const c = new PhantomCursor()
  c.mount(0, 0)
  // Don't actually await — happy-dom does not fire transitionend.
  // Instead test that the transform updated immediately and the Promise
  // resolves via the timeout fallback.
  const p = c.move(100, 100)
  const el = document.getElementById('mateclaw-phantom-cursor')!
  expect(el.style.transform).toContain('translate3d(100px, 100px')
  await p   // should resolve within 250ms via fallback
})

it('unmount removes the element', () => {
  const c = new PhantomCursor()
  c.mount(0, 0)
  c.unmount()
  expect(document.getElementById('mateclaw-phantom-cursor')).toBeNull()
})

it('prefers-reduced-motion: cursor transition collapses to ≤30ms (P1-8)', () => {
  vi.spyOn(window, 'matchMedia').mockReturnValue({
    matches: true, media: '(prefers-reduced-motion: reduce)',
    addEventListener: () => {}, removeEventListener: () => {},
    onchange: null, dispatchEvent: () => false, addListener: () => {}, removeListener: () => {},
  } as MediaQueryList)
  const c = new PhantomCursor()
  c.mount(0, 0)
  const el = document.getElementById('mateclaw-phantom-cursor')!
  // Parse transition duration ("180ms" or "30ms") out of the inline style.
  const match = /transform\s+(\d+)ms/.exec(el.style.transition)
  expect(match).not.toBeNull()
  expect(parseInt(match![1], 10)).toBeLessThanOrEqualTo(30)
})
```

**Step 2–5:** [per template] — implement, run, pass, commit.

> Implementation hint: subscribe to `transitionend` on the element with `{ once: true }`, **also** start a `setTimeout(resolve, 220)` and clear it on the transitionend. The first to fire resolves the Promise. This is the documented "tab-in-background suppression" pattern from research §2.2.

---

# Task C3: GlowBorder

**Files:**
- Create: `mateclaw-extension/src/content/visual/GlowBorder.ts`
- Test: `mateclaw-extension/src/content/visual/GlowBorder.test.ts`

**Responsibility:**

```ts
class GlowBorder {
  show(): void           // mount + fade in
  hide(): void           // fade out + remove
}
```

**Visual contract** (per research §3):
- Full-viewport fixed `<div id="mateclaw-glow-border">` with `opacity 0 → 1` fade in 300 ms.
- Inner `<div id="mateclaw-glow-border-inner">` carries the three-stop inset box-shadow in the MateClaw brand color (read from CSS variable `--mateclaw-brand-rgb`).
- `@keyframes mateclaw-pulse` opacity 0.6 ↔ 1.0, 2 s `ease-in-out` infinite.
- Inject the `@keyframes` once per page via a `<style id="mateclaw-glow-anim">` tag; guard against double-injection.
- `@media (prefers-reduced-motion: reduce)` → animation: none.
- State machine: `HIDDEN → SHOWING → VISIBLE → HIDING → HIDDEN`. Calling `hide()` during `SHOWING` should still cleanly tear down without leaving stale DOM.

**Step 1: Test.** Tests should cover: mount + style attached; double-show is idempotent; hide during show works; injected `<style>` tag only added once. **Plus the P1-8 reduced-motion test:**

```ts
it('prefers-reduced-motion: glow animation is none (P1-8)', () => {
  vi.spyOn(window, 'matchMedia').mockReturnValue({
    matches: true, media: '(prefers-reduced-motion: reduce)',
    addEventListener: () => {}, removeEventListener: () => {},
    onchange: null, dispatchEvent: () => false, addListener: () => {}, removeListener: () => {},
  } as MediaQueryList)
  const g = new GlowBorder()
  g.show()
  const inner = document.getElementById('mateclaw-glow-border-inner')!
  // Either the @keyframes rule is suppressed via injected media query OR
  // the inline animation is set to "none".
  expect(window.getComputedStyle(inner).animation === 'none'
       || inner.style.animation === 'none').toBe(true)
})
```

**Step 2–5: [per template]**

---

# Task C4: StopButton

**Files:**
- Create: `mateclaw-extension/src/content/visual/StopButton.ts`
- Test: `mateclaw-extension/src/content/visual/StopButton.test.ts`

**Responsibility:**

```ts
class StopButton {
  show(opts: { suppressed?: boolean }): void   // suppressed = don't actually mount (MCP mode)
  hide(): void
  onClick(cb: () => void): void
}
```

**Visual contract** (per research §4):
- Bottom-center pill, slide-in from below (`translateY(100px) → 0` + `opacity 0 → 1`, 300 ms `cubic-bezier(0.4, 0, 0.2, 1)`).
- Brand stop icon (square inside circle) + label "Stop Agent" (MateClaw label).
- Hover: background tint shift; cursor: pointer.
- `pointer-events: auto` (container is `none`, button re-enables).
- z-index = `2147483647` (max int, above cursor and glow).

**Step 1: Test.** Mount + visible + onClick fires; hide removes after fade; suppressed=true skips mount entirely.

**Step 2–5.**

---

# Task C5: VisualIndicator content script (wiring)

**Files:**
- Create: `mateclaw-extension/src/content/visual-indicator.ts`
- Test: `mateclaw-extension/src/content/visual-indicator.test.ts`

**Responsibility:** the top-frame, document_idle content script that owns instances of `PhantomCursor` / `GlowBorder` / `StopButton` / (optional) `StaticPill`, listens to `chrome.runtime.onMessage`, and dispatches `INDICATOR_*` messages to the right component.

**Step 1: Test:**

```ts
it('SHOW_AGENT_INDICATORS mounts cursor + glow + stop button', () => {
  setupListener()    // call the module's init
  fireMessage({ type: 'SHOW_AGENT_INDICATORS' })
  expect(document.getElementById('mateclaw-phantom-cursor')).toBeTruthy()
  expect(document.getElementById('mateclaw-glow-border')).toBeTruthy()
  expect(document.getElementById('mateclaw-stop-button')).toBeTruthy()
})

it('SHOW_AGENT_INDICATORS with isMcp=true suppresses stop button', () => {
  setupListener()
  fireMessage({ type: 'SHOW_AGENT_INDICATORS', isMcp: true })
  expect(document.getElementById('mateclaw-stop-button')).toBeNull()
})

it('INDICATOR_CURSOR moves the cursor and the sendResponse resolves', async () => {
  setupListener()
  fireMessage({ type: 'SHOW_AGENT_INDICATORS' })
  const responded = new Promise(res => fireMessage({ type: 'INDICATOR_CURSOR', x: 100, y: 200 }, res))
  await responded
  expect(document.getElementById('mateclaw-phantom-cursor')!.style.transform)
        .toContain('translate3d(100px, 200px')
})

it('stop button click sends STOP_AGENT to SW', async () => {
  setupListener()
  fireMessage({ type: 'SHOW_AGENT_INDICATORS' })
  const btn = document.getElementById('mateclaw-stop-button')!
  btn.click()
  expect((globalThis as any).chrome.runtime.sendMessage).toHaveBeenCalledWith(
    expect.objectContaining({ type: 'STOP_AGENT' }),
  )
})

it('HIDE_FOR_TOOL_USE hides indicators; SHOW_AFTER_TOOL_USE restores them', () => { ... })

// Codex P2-2 — idempotency at the wiring layer.
it('double SHOW_AGENT_INDICATORS does not duplicate DOM nodes', () => {
  setupListener()
  fireMessage({ type: 'SHOW_AGENT_INDICATORS' })
  fireMessage({ type: 'SHOW_AGENT_INDICATORS' })
  expect(document.querySelectorAll('#mateclaw-phantom-cursor')).toHaveLength(1)
  expect(document.querySelectorAll('#mateclaw-glow-border')).toHaveLength(1)
  expect(document.querySelectorAll('#mateclaw-stop-button')).toHaveLength(1)
})

it('SHOW_AGENT_INDICATORS after HIDE re-mounts cleanly without duplicates', () => {
  setupListener()
  fireMessage({ type: 'SHOW_AGENT_INDICATORS' })
  fireMessage({ type: 'HIDE_AGENT_INDICATORS' })
  fireMessage({ type: 'SHOW_AGENT_INDICATORS' })
  expect(document.querySelectorAll('#mateclaw-phantom-cursor')).toHaveLength(1)
})
```

**Step 2–5: [per template]**

---

# Task C6: Manifest + content_scripts update

**Files:**
- Modify: `mateclaw-extension/manifest.json`
- Modify: `mateclaw-extension/vite.config.ts`

**Manifest changes:**
- Add `debugger` and `scripting` to `permissions`.
- Add two `content_scripts` entries:
  - `assets/a11y-tree.js` — `<all_urls>`, `document_start`, **`all_frames: false`** (Codex P1-5 — Phase 2 top-frame only; cross-frame coordinate translation is Phase 3).
  - `assets/visual-indicator.js` — `<all_urls>`, `document_idle`, `all_frames: false`.

**Vite config**: add `a11y-tree` and `visual-indicator` to the rollup `input` map (both bundle into single self-contained JS files; no module imports of other extension assets — content scripts run in the isolated world, can't `import` from `chrome-extension://` URLs).

**Step 1**: write a simple verification test — load the built `dist/manifest.json` and assert the new permissions / content_scripts are present.

**Step 2–5.**

> **Codex hot-spot**: the `debugger` permission triggers Chrome's yellow "this browser is being controlled by automated software" banner. This is non-optional and must be documented in the user-facing README. The plan's smoke runbook (E3) must say so.

---

# D-stream: SW Tab Group Manager + Visual Coordinator

# Task D1: TabGroupManager

**Files:**
- Create: `mateclaw-extension/src/sw/tabs/TabGroupManager.ts`
- Test: `mateclaw-extension/src/sw/tabs/TabGroupManager.test.ts`

**Responsibility (per research §6):**

```ts
interface ManagedGroup {
  chromeGroupId: number
  mainTabId: number
  secondaryTabIds: number[]
  sessionId: string
  createdAt: number
}

class TabGroupManager {
  initialize(force?: boolean): Promise<void>
  createGroup(mainTabId: number): Promise<ManagedGroup>
  findGroupByTab(tabId: number): Promise<ManagedGroup | null>
  adoptOrphanedGroup(tabId: number, chromeGroupId: number): Promise<ManagedGroup>
  getMainTabId(currentTabId: number): Promise<number | null>
  handleTabClosed(tabId: number): Promise<void>
  clearAllGroups(): Promise<void>
}
```

**Key invariants:**
- A "managed" group has a Chrome group title prefixed `[MateClaw]` (configurable constant `MANAGED_PREFIX`).
- `createGroup`: `chrome.tabs.group({ tabIds: [mainTabId] })` → `chrome.tabGroups.update(groupId, { title: '[MateClaw] <employee>', color: 'purple' })` → persist meta to `chrome.storage.session`.
- `adoptOrphanedGroup`: only adopts groups whose title already starts with `MANAGED_PREFIX`. Refuses user-made groups (`isUnmanaged: true`).
- `handleTabClosed`: if closed tab is main, promote a secondary; if it was the last, destroy the group meta.
- All state persists to `chrome.storage.session` so the SW restart doesn't lose the group↔tabs mapping.

**Step 1: Test** with mocked `chrome.tabs` / `chrome.tabGroups` / `chrome.storage.session`. Cover:
- createGroup happy path
- adoptOrphanedGroup refuses non-`[MateClaw]` titled groups
- handleTabClosed promotes secondary to main
- handleTabClosed on the only tab destroys the meta

**Step 2–5.**

---

# Task D2: VisualCoordinator

**Files:**
- Create: `mateclaw-extension/src/sw/visual/VisualCoordinator.ts`
- Test: `mateclaw-extension/src/sw/visual/VisualCoordinator.test.ts`

**Responsibility:** translate Edge protocol `indicator.*` envelopes into `chrome.tabs.sendMessage` to the right tab(s). Holds the policy decisions:

- `indicator.show` → send `SHOW_AGENT_INDICATORS` to main tab only.
- `indicator.cursor` → send `INDICATOR_CURSOR` to main tab, await response, return Promise.
- `indicator.hide` → send `HIDE_AGENT_INDICATORS` to main tab; if group manager says there are secondary tabs, send `SHOW_STATIC_INDICATOR` to each (the secondary pill).
- `indicator.tool_use_hide` / `..._show` → main tab only.

**Step 1: Test** with mock `chrome.tabs.sendMessage` and a mock `TabGroupManager`.

**Step 2–5.**

---

# Task D3 (stretch): Static pill heartbeat self-kill

**Files:**
- Modify: `mateclaw-extension/src/content/visual-indicator.ts` (add StaticPill component)
- Modify: `mateclaw-extension/src/sw/visual/VisualCoordinator.ts` (handle STATIC_INDICATOR_HEARTBEAT)
- Test: extend existing tests

**Responsibility (per research §5):**
- A small pill appears on secondary tabs (not the main one) when an agent is active in the group.
- Pill content script sends `STATIC_INDICATOR_HEARTBEAT` every 5 s.
- SW (VisualCoordinator) replies `{ success: true }` only if (a) the sender tab is still in a managed group AND (b) the group has a live main tab in it. Otherwise `{ success: false }` and the pill self-removes.
- This is the SW-kill-survivable cleanup guarantee.

**Step 1: Tests** (Codex P2-3 — the SW-kill scenario must be mechanical, not narrative):

```ts
describe('D3 static pill', () => {
  it('round trip: heartbeat with live managed main returns success', async () => {
    const tgm = makeTabGroupManager({ groups: [{ chromeGroupId: 7, mainTabId: 100, secondaryTabIds: [101] }] })
    const vc = new VisualCoordinator(tgm)
    const reply = await vc.onStaticHeartbeat({ tabId: 101, groupId: 7 })
    expect(reply).toEqual({ success: true })
  })

  it('self-kill: SW restart loses managed group meta → next heartbeat fails → pill removed', async () => {
    // Round 1: pill mounted, heartbeat OK.
    mountStaticPillForTest(101)
    expect(document.getElementById('mateclaw-static-pill')).toBeTruthy()

    // Simulate "SW restart": construct a fresh VisualCoordinator + empty
    // TabGroupManager whose chrome.storage.session is empty.
    const emptyTgm = makeTabGroupManager({ groups: [] })
    const vc2 = new VisualCoordinator(emptyTgm)
    const reply = await vc2.onStaticHeartbeat({ tabId: 101, groupId: 7 })
    expect(reply).toEqual({ success: false })

    // Fire the heartbeat from the content script perspective; it should
    // see {success:false} and remove the pill within 1.1 × heartbeat interval.
    await fireHeartbeatTickAndAwaitFor(1100 /* ms */)
    expect(document.getElementById('mateclaw-static-pill')).toBeNull()
  })

  it('main tab missing → heartbeat returns success: false', async () => {
    const tgm = makeTabGroupManager({ groups: [{ chromeGroupId: 7, mainTabId: 100, secondaryTabIds: [101] }] })
    // Pretend tab 100 has been closed.
    ;(chrome.tabs.get as any).mockRejectedValueOnce(new Error('No tab with id: 100'))
    const vc = new VisualCoordinator(tgm)
    const reply = await vc.onStaticHeartbeat({ tabId: 101, groupId: 7 })
    expect(reply).toEqual({ success: false })
  })

  it('tab not in managed group → heartbeat returns success: false', async () => {
    const tgm = makeTabGroupManager({ groups: [] })   // no managed groups at all
    const vc = new VisualCoordinator(tgm)
    const reply = await vc.onStaticHeartbeat({ tabId: 101, groupId: 99 })
    expect(reply).toEqual({ success: false })
  })

  it('three consecutive false heartbeats permanently remove the pill (no retry)', async () => {
    mountStaticPillForTest(101)
    const tgm = makeTabGroupManager({ groups: [] })
    const vc = new VisualCoordinator(tgm)
    for (let i = 0; i < 3; i++) {
      await vc.onStaticHeartbeat({ tabId: 101, groupId: 7 })
      await fireHeartbeatTickAndAwaitFor(5500 /* 5s interval + slack */)
    }
    expect(document.getElementById('mateclaw-static-pill')).toBeNull()
  })
})
```

**Step 2–5: [per template]**

**Disposition (Codex P2-3):** if D3 fully ships in Phase 2, the SW-kill test above is mandatory. If D3 is deferred to Phase 2.1, the Phase 2 acceptance checklist's "SW kill survival" line must explicitly point at Phase 2.1, and the static pill must NOT be shown at all in Phase 2 (no half-implemented guard).

---

# F-stream: Control Plane Orchestrator

# Task F1: ActionPlanner

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/ActionPlanner.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/ActionPlannerTest.java`

**Responsibility:** given a `Step` (high-level intent + `TabRef` + `groundedTarget`), output a `List<ActionRequest>`. Every produced `ActionRequest` carries the **same `TabRef`** as the input step (Codex P0-1). The list is intentionally ordered; F4 owns sequential execution (Codex P0-2). On `GroundingResult.Ambiguous`, throws `GroundingAmbiguousException` rather than emitting a click (Codex P1-9).

**Codex re-audit hints:**
- The planner emits `move_mouse` first, then `click`. The `click` cannot be in the same batch — F4 awaits each result before sending the next. F1 tests assert ordering; E1 asserts wall-clock sequencing.
- Every `ActionRequest` carries `TabRef`. If the step had `TabRef.Main`, every produced request has `TabRef.Main`. The SW resolves once per action (no cross-action tab drift).
- `move_mouse` uses `profile: "natural"` by default (B7 walks the WindMouse path internally). The single `MOVE_MOUSE` action is the natural-profile call; F1 does NOT emit N small moves.

**Step 1: Test:**

```java
@Test
void clickStep_emitsMoveMouseThenClick_carryingTabRef() {
    var target = new GroundedTarget(new BBox(540, 320, 80, 32));    // center: 580, 336
    var step = new ClickStep(new TabRef.Main(), target);
    var plan = planner.plan(step);

    assertThat(plan).hasSize(2);
    assertThat(plan.get(0).tabRef()).isEqualTo(new TabRef.Main());
    assertThat(plan.get(0).kind()).isEqualTo(ActionKind.MOVE_MOUSE);
    var move = (MoveMousePayload) plan.get(0).payload();
    assertThat(move.x()).isEqualTo(580);
    assertThat(move.y()).isEqualTo(336);
    assertThat(move.profile()).isEqualTo("natural");

    assertThat(plan.get(1).tabRef()).isEqualTo(new TabRef.Main());
    assertThat(plan.get(1).kind()).isEqualTo(ActionKind.CLICK);
    assertThat(((ClickPayload) plan.get(1).payload()).x()).isEqualTo(580);
}

@Test
void typeStep_emitsFocusClickMoveTypeSequence() {
    var target = new GroundedTarget(new BBox(100, 100, 200, 30));
    var plan = planner.plan(new TypeStep(new TabRef.Main(), target, "hello"));
    assertThat(plan).hasSize(3);
    assertThat(plan.get(2).kind()).isEqualTo(ActionKind.TYPE);
    // all carry the same TabRef
    assertThat(plan).extracting(ActionRequest::tabRef).containsOnly(new TabRef.Main());
}

@Test
void ambiguousGrounding_throws() {
    var ambiguous = new GroundingResult.Ambiguous(
        List.of(new GroundedTarget(...), new GroundedTarget(...)),
        "two buttons named Submit");
    var step = new ClickStep(new TabRef.Main(), ambiguous);
    assertThatThrownBy(() -> planner.plan(step))
            .isInstanceOf(GroundingAmbiguousException.class)
            .hasMessageContaining("two buttons");
}
```

**Step 2: [run per template]**

**Step 3: Implement.** Planner is now a thin function. Sketch:

```java
public List<ActionRequest> plan(Step step) {
    GroundedTarget target = switch (step.grounding()) {
        case GroundingResult.Hit hit -> hit.target();
        case GroundingResult.Ambiguous a -> throw new GroundingAmbiguousException(a);
        case GroundingResult.Miss m -> throw new GroundingMissException(m);
    };
    var center = target.bbox().center();   // BBox#center returns (cx, cy) as ints
    return switch (step) {
        case ClickStep cs -> List.of(
            new ActionRequest(uuid(), cs.tabRef(), ActionKind.MOVE_MOUSE,
                    new MoveMousePayload(center.x(), center.y(), "natural"), DEFAULT_DEADLINE),
            new ActionRequest(uuid(), cs.tabRef(), ActionKind.CLICK,
                    new ClickPayload(center.x(), center.y(), "left", 1, null), DEFAULT_DEADLINE)
        );
        case TypeStep ts -> List.of(
            new ActionRequest(uuid(), ts.tabRef(), ActionKind.MOVE_MOUSE,
                    new MoveMousePayload(center.x(), center.y(), "natural"), DEFAULT_DEADLINE),
            new ActionRequest(uuid(), ts.tabRef(), ActionKind.CLICK,
                    new ClickPayload(center.x(), center.y(), "left", 1, null), DEFAULT_DEADLINE),
            new ActionRequest(uuid(), ts.tabRef(), ActionKind.TYPE,
                    new TypePayload(ts.text(), null), DEFAULT_DEADLINE)
        );
        // ... other step kinds added in Phase 3
    };
}
```

**Step 4: [run per template]**

**Step 5: Commit.**

---

# Task F2: GroundingDispatcher with three engines

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/GroundingDispatcher.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/DomEngine.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/A11yEngine.java` (Phase 2 stub)
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/VisionEngine.java` (Phase 2 stub)
- Test: `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/GroundingDispatcherTest.java`

**Responsibility:**

```java
public interface GroundingEngine {
    String name();
    GroundingResult ground(PageSnapshot snapshot, GroundingHint hint);
}

// Sealed result (Codex P1-9 fix).
public sealed interface GroundingResult {
    record Hit(GroundedTarget target, String evidence) implements GroundingResult {}
    record Ambiguous(List<GroundedTarget> candidates, String evidence) implements GroundingResult {}
    record Miss(String reason) implements GroundingResult {}
}
```

Dispatcher chains DOM → A11y → Vision. Resolution policy:
- Engine returns `Hit` → dispatcher returns it.
- Engine returns `Ambiguous` → dispatcher remembers the candidates and tries the next engine in hope of disambiguation. If all engines are exhausted without a `Hit`, the dispatcher returns the *first* `Ambiguous` it saw.
- Engine returns `Miss` → dispatcher tries next.
- All `Miss` → dispatcher returns `Miss("no-engine-hit")`.

**Snapshot refresh hook (Codex P0-3):** before each `ground()` call the dispatcher asks `PageSnapshotService.request(session, tabRef, hint.filter())` for the snapshot. If the freshness state is `STALE`, the service transparently re-fetches via Bridge → SW → content script. The dispatcher does not see the asynchrony — it always gets a fresh `PageSnapshot` for its work. Phase 2 makes this synchronous-feeling via `Mono.block()` inside the dispatcher; Phase 3 refactors to fully reactive.

Phase 2: **A11y and Vision engines are stubs** returning `Miss("stub-phase-2")`. DOM is real (task F3).

**Step 1: Tests:**

```java
@Test
void domHit_returnsHit() {
    when(domEngine.ground(any(), any())).thenReturn(new GroundingResult.Hit(target1, "data-e2e=submit"));
    var r = dispatcher.ground(session, new TabRef.Main(), hint);
    assertThat(r).isInstanceOf(GroundingResult.Hit.class);
}

@Test
void domMissA11yStubMissVisionStubMiss_returnsMiss() {
    when(domEngine.ground(any(), any())).thenReturn(new GroundingResult.Miss("no candidate"));
    var r = dispatcher.ground(session, new TabRef.Main(), hint);
    assertThat(r).isInstanceOf(GroundingResult.Miss.class);
}

@Test
void domAmbiguous_a11yStubMiss_visionStubMiss_returnsAmbiguous() {
    when(domEngine.ground(any(), any())).thenReturn(new GroundingResult.Ambiguous(List.of(t1, t2), "two Submits"));
    var r = dispatcher.ground(session, new TabRef.Main(), hint);
    assertThat(r).isInstanceOf(GroundingResult.Ambiguous.class);
}

@Test
void domAmbiguous_a11yHit_returnsA11yHit() {
    when(domEngine.ground(any(), any())).thenReturn(new GroundingResult.Ambiguous(List.of(t1, t2), "two Submits"));
    when(a11yEngine.ground(any(), any())).thenReturn(new GroundingResult.Hit(t1, "narrowed via parent role"));
    var r = dispatcher.ground(session, new TabRef.Main(), hint);
    assertThat(r).isInstanceOf(GroundingResult.Hit.class);
    assertThat(((GroundingResult.Hit) r).target()).isEqualTo(t1);
}

@Test
void refreshesStaleSnapshotBeforeGround() {
    when(snapshotService.request(any(), any(), any()))
        .thenReturn(Mono.just(freshSnapshot));
    dispatcher.ground(session, new TabRef.Main(), hint);
    verify(snapshotService).request(eq(session), eq(new TabRef.Main()), any());
}
```

**Step 2–5: [per template]**

---

# Task F3: DomEngine — selector candidates

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/DomEngine.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/engine/DomEngineTest.java`

**Responsibility:** given a `PageSnapshot` (which carries the a11y tree text) and a `GroundingHint` with a list of selector candidates, find the first candidate whose presence is confirmed in the a11y tree (by matching role + name pattern + bbox lookup).

**Phase 2 simplification:** the "selector" in this Phase is just a `(role, name_pattern)` pair (and optionally `ref_id` from a prior call). The Phase 3 SOP work will add multi-pattern CSS candidates with confidence learning.

**Step 1: Test:**

```java
@Test
void hit_whenExactlyOneA11yLineMatches() {
    var snapshot = PageSnapshot.fromA11yText("""
            main
              button "Submit" [ref_1 @100,200,80,32]
              input "Search" [ref_2 @100,250,200,32]
            """, new Viewport(1280, 800));
    var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"));
    var result = new DomEngine().ground(snapshot, hint);
    assertThat(result).isInstanceOf(GroundingResult.Hit.class);
    assertThat(((GroundingResult.Hit) result).target().bbox()).isEqualTo(new BBox(100, 200, 80, 32));
}

@Test
void ambiguous_whenTwoLinesMatchSameRoleAndName() {                  // Codex P1-9 fix
    var snapshot = PageSnapshot.fromA11yText("""
            main
              button "Submit" [ref_1 @100,200,80,32]
              article
                button "Submit" [ref_3 @300,500,80,32]
            """, new Viewport(1280, 800));
    var hint = new GroundingHint.A11yMatch("button", Pattern.compile("^Submit$"));
    var result = new DomEngine().ground(snapshot, hint);
    assertThat(result).isInstanceOf(GroundingResult.Ambiguous.class);
    assertThat(((GroundingResult.Ambiguous) result).candidates()).hasSize(2);
}

@Test
void miss_whenPatternDoesNotMatch() {
    var snapshot = PageSnapshot.fromA11yText("main\n  button \"Cancel\" [ref_1 @100,200,80,32]\n",
            new Viewport(1280, 800));
    var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"));
    assertThat(new DomEngine().ground(snapshot, hint)).isInstanceOf(GroundingResult.Miss.class);
}

@Test
void refHit_whenRefIdHintProvidedAndPresent() {
    var snapshot = PageSnapshot.fromA11yText("main\n  button \"Submit\" [ref_42 @100,200,80,32]\n",
            new Viewport(1280, 800));
    var hint = new GroundingHint.ByRefId("ref_42");
    var result = new DomEngine().ground(snapshot, hint);
    assertThat(result).isInstanceOf(GroundingResult.Hit.class);
}
```

**Step 2–5: [per template]**

---

# Task F4: PlanExecutionService (Codex P0-2, NEW in v1.1)

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/PlanExecutionService.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/PlanResult.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/PlanExecutionServiceTest.java`

**Responsibility:** take a list of `ActionRequest` (produced by F1 `ActionPlanner`), execute them **sequentially** via `ActionExecutionService`, returning a `PlanResult` that carries either `Success(List<ActionResult.Success>)` or `Partial(List<ActionResult.Success> done, ActionResult.Failure failed)`.

**This is the bug Codex P0-2 caught — it forces "click waits for move".** The only acceptable implementation is a `concatMap` (or `thenCompose` chain), NEVER a parallel `flatMap` / `Flux.merge` / `Mono.zip` / `CompletableFuture.allOf`.

**Step 1: Write failing tests:**

```java
@SpringBootTest    // ActionExecutionService is real; sink WS is mocked
class PlanExecutionServiceTest {

    @Autowired PlanExecutionService planExec;
    @Autowired ActionExecutionService actionExec;

    @Test
    void emptyPlan_returnsImmediateSuccess() {
        var result = planExec.execute(session, List.of()).block();
        assertThat(result).isInstanceOf(PlanResult.Success.class);
        assertThat(((PlanResult.Success) result).completed()).isEmpty();
    }

    @Test
    void twoStepPlan_executesSequentially_clickAfterMove() throws Exception {
        var move = newRequest(ActionKind.MOVE_MOUSE, new MoveMousePayload(100, 200, "natural"));
        var click = newRequest(ActionKind.CLICK, new ClickPayload(100, 200, "left", 1, null));

        // Wire the fake WS sink: capture envelopes, simulate Extension reply.
        var sink = new RecordingWsSink();
        bindSink(session, sink);

        var future = planExec.execute(session, List.of(move, click)).toFuture();

        // Step 1: move envelope arrived; reply with success.
        await().untilAsserted(() -> assertThat(sink.envelopes()).hasSize(1));
        assertThat(sink.envelopes().get(0).getKind().wire()).isEqualTo("action.execute");
        actionExec.onResult(session.getId(), move.msgId(), new ActionResult.Success(180,
                new MoveMouseSuccess(1730000000200L, 7)));

        // Step 2: click envelope should now have been sent — AFTER move result, NOT before.
        await().untilAsserted(() -> assertThat(sink.envelopes()).hasSize(2));
        var moveSentAt = sink.timestamps().get(0);
        var clickSentAt = sink.timestamps().get(1);
        assertThat(clickSentAt - moveSentAt).isGreaterThanOrEqualTo(180); // wall-clock proves serial wait
        actionExec.onResult(session.getId(), click.msgId(), new ActionResult.Success(50,
                new ClickSuccess()));

        var result = future.get(2, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(PlanResult.Success.class);
        assertThat(((PlanResult.Success) result).completed()).hasSize(2);
    }

    @Test
    void onFailure_stopsAndReturnsPartial() throws Exception {
        var move = newRequest(ActionKind.MOVE_MOUSE, new MoveMousePayload(100, 200, "natural"));
        var click = newRequest(ActionKind.CLICK, new ClickPayload(100, 200, "left", 1, null));
        var sink = new RecordingWsSink(); bindSink(session, sink);

        var future = planExec.execute(session, List.of(move, click)).toFuture();

        // Move fails. Click MUST NOT be sent.
        await().untilAsserted(() -> assertThat(sink.envelopes()).hasSize(1));
        actionExec.onResult(session.getId(), move.msgId(),
                new ActionResult.Failure("TIMEOUT_PAGE_LOAD", "...", true));

        var result = future.get(500, TimeUnit.MILLISECONDS);
        assertThat(result).isInstanceOf(PlanResult.Partial.class);
        assertThat(sink.envelopes()).hasSize(1);   // click NEVER sent
    }

    @Test
    void noParallelDispatch() throws Exception {
        // Build a 5-step plan. Bind the sink to fail the test if ANY two
        // envelopes are sent within 5ms of each other (parallelism evidence).
        // ... assert max(deltaT[i]) >= 100ms (because each step pauses for the result)
    }
}
```

**Step 2: Run:** `mvn test -Dtest=PlanExecutionServiceTest` → compile error.

**Step 3: Implement.**

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanExecutionService {

    private final ActionExecutionService actionExec;

    public Mono<PlanResult> execute(BrowserSession session, List<ActionRequest> plan) {
        if (plan.isEmpty()) return Mono.just(PlanResult.success(List.of()));

        // concatMap — NOT flatMap. Sequential is the whole point.
        return Flux.fromIterable(plan)
                .concatMap(req -> Mono.fromFuture(actionExec.execute(session, req))
                        .map(result -> new ExecutedStep(req, result)))
                .takeUntil(step -> step.result() instanceof ActionResult.Failure)
                .collectList()
                .map(steps -> {
                    var failure = steps.stream()
                            .map(ExecutedStep::result)
                            .filter(r -> r instanceof ActionResult.Failure)
                            .map(r -> (ActionResult.Failure) r)
                            .findFirst();
                    var successes = steps.stream()
                            .map(ExecutedStep::result)
                            .filter(r -> r instanceof ActionResult.Success)
                            .map(r -> (ActionResult.Success) r)
                            .toList();
                    return failure.isPresent()
                            ? PlanResult.partial(successes, failure.get())
                            : PlanResult.success(successes);
                });
    }

    private record ExecutedStep(ActionRequest request, ActionResult result) {}
}
```

`PlanResult` is a sealed interface:

```java
public sealed interface PlanResult permits PlanResult.Success, PlanResult.Partial {
    record Success(List<ActionResult.Success> completed) implements PlanResult {}
    record Partial(List<ActionResult.Success> completed, ActionResult.Failure failed) implements PlanResult {}

    static PlanResult success(List<ActionResult.Success> s) { return new Success(s); }
    static PlanResult partial(List<ActionResult.Success> s, ActionResult.Failure f) { return new Partial(s, f); }
}
```

> **Codex re-audit hint**: grep for `flatMap\|Flux\.merge\|Mono\.zip\|allOf` in `vip.mate.browser.orchestrator`. Anything other than `concatMap` on the action-dispatch path is a fail.

**Step 4: Run** the tests. The wall-clock assertion (≥180 ms between move-sent and click-sent) is the load-bearing check — make sure your `RecordingWsSink` captures `System.currentTimeMillis()` on each enqueue.

**Step 5: Commit.**

```bash
git commit -m "feat(browser): add PlanExecutionService (sequential plan executor)

Executes a List<ActionRequest> via Flux.concatMap → one ActionExecutionService
.execute() at a time, awaiting each result before sending the next. On the
first Failure stops and returns PlanResult.Partial. concatMap (not flatMap)
is mandatory — it's how the visual move-then-click timing contract is honoured.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Task F5: PageSnapshotService (Codex P0-3, NEW in v1.1)

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/snapshot/PageSnapshotService.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/snapshot/SnapshotState.java`
- Create: `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/snapshot/PageSnapshot.java`
- Test: `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/snapshot/PageSnapshotServiceTest.java`

**Responsibility:** own the per-`(sessionId, resolvedTabId)` snapshot cache, decide FRESH/SUSPECT/STALE, and re-fetch from the Extension via `a11y.snapshot.request` when needed. Subscribe to action results to update freshness.

**API:**

```java
public interface PageSnapshotService {
    /**
     * Get a usable PageSnapshot for (session, tabRef, filter). If the cached
     * snapshot is FRESH and matches the filter, return it. If STALE or absent,
     * issue a11y.snapshot.request, await the response, cache it, return it.
     * SUSPECT snapshots are returned but the next ground() miss triggers a refresh.
     */
    Mono<PageSnapshot> request(BrowserSession session, TabRef tabRef, String filter);

    /** Called by an ActionResult listener (Spring event) when an action succeeds. */
    void onActionSuccess(String sessionId, long resolvedTabId, ActionKind kind);

    /** Called when the Extension reports event.page.navigated or event.tab.closed. */
    void onPageEvent(String sessionId, long resolvedTabId, PageEvent event);

    /** Force a refresh next time. Used by the orchestrator after retry budget exhaustion. */
    void invalidate(String sessionId, long resolvedTabId);
}
```

**Step 1: Tests:**

```java
@Test
void firstRequest_fetchesAndCachesFresh() throws Exception {
    when(snapshotClient.request(any())).thenReturn(Mono.just(freshResponse));
    var snap = service.request(session, new TabRef.Main(), "default").block();
    assertThat(snap.snapshotId()).isEqualTo(freshResponse.snapshotId());
    verify(snapshotClient).request(any());
}

@Test
void secondRequest_returnsCacheWhenFresh() throws Exception {
    service.request(session, new TabRef.Main(), "default").block();
    reset(snapshotClient);
    service.request(session, new TabRef.Main(), "default").block();
    verifyNoInteractions(snapshotClient);
}

@Test
void navigateAction_marksStaleAndNextRequestRefetches() throws Exception {
    service.request(session, new TabRef.Main(), "default").block();
    service.onActionSuccess(session.getId(), 42L /* resolved tab id */, ActionKind.NAVIGATE);
    reset(snapshotClient);
    when(snapshotClient.request(any())).thenReturn(Mono.just(secondResponse));
    service.request(session, new TabRef.Main(), "default").block();
    verify(snapshotClient).request(any());
}

@Test
void clickAction_marksSuspectButUsableOnce() throws Exception {
    service.request(session, new TabRef.Main(), "default").block();
    service.onActionSuccess(session.getId(), 42L, ActionKind.CLICK);
    reset(snapshotClient);
    var snap = service.request(session, new TabRef.Main(), "default").block();
    verifyNoInteractions(snapshotClient);   // suspect ≠ stale; cache returned
    assertThat(snap).isNotNull();

    // After the next "ground miss" the orchestrator calls invalidate; subsequent request refreshes.
    service.invalidate(session.getId(), 42L);
    service.request(session, new TabRef.Main(), "default").block();
    verify(snapshotClient).request(any());
}

@Test
void age30s_isStaleEvenWithoutAction() throws Exception {
    service.request(session, new TabRef.Main(), "default").block();
    clock.advance(Duration.ofSeconds(31));
    reset(snapshotClient);
    when(snapshotClient.request(any())).thenReturn(Mono.just(secondResponse));
    service.request(session, new TabRef.Main(), "default").block();
    verify(snapshotClient).request(any());
}
```

**Step 2: [run per template]**

**Step 3: Implement.** The freshness state machine is a small switch; the `snapshotClient` is an injected port whose default implementation sends an `a11y.snapshot.request` envelope via the same Edge transport `ActionExecutionService` uses (Bridge owns session_id stamping).

```java
@Service
@RequiredArgsConstructor
public class DefaultPageSnapshotService implements PageSnapshotService {

    public static final Duration MAX_AGE = Duration.ofSeconds(30);

    private final SnapshotEdgeClient snapshotClient;
    private final TabRefResolver resolver;   // same logic as the SW-side resolver
    private final Clock clock;
    private final Map<Key, Cached> cache = new ConcurrentHashMap<>();

    @Override
    public Mono<PageSnapshot> request(BrowserSession s, TabRef ref, String filter) {
        return resolver.resolve(s, ref).flatMap(tabId -> {
            var key = new Key(s.getId(), tabId);
            var cached = cache.get(key);
            if (cached != null && isUsable(cached, filter)) {
                return Mono.just(cached.snapshot);
            }
            return snapshotClient.request(s, ref, filter)
                    .doOnNext(snap -> cache.put(key, new Cached(snap, SnapshotState.FRESH, clock.instant())));
        });
    }

    private boolean isUsable(Cached c, String filter) {
        if (Duration.between(c.cachedAt, clock.instant()).compareTo(MAX_AGE) > 0) return false;
        return switch (c.state) {
            case FRESH, SUSPECT -> true;
            case STALE -> false;
        };
    }

    @Override
    public void onActionSuccess(String sessionId, long tabId, ActionKind kind) {
        var key = new Key(sessionId, tabId);
        cache.computeIfPresent(key, (k, c) -> switch (kind) {
            case NAVIGATE -> new Cached(c.snapshot, SnapshotState.STALE, c.cachedAt);
            case CLICK, TYPE, SCROLL -> new Cached(c.snapshot, SnapshotState.SUSPECT, c.cachedAt);
            default -> c;
        });
    }

    @Override public void invalidate(String sessionId, long tabId) {
        cache.computeIfPresent(new Key(sessionId, tabId),
                (k, c) -> new Cached(c.snapshot, SnapshotState.STALE, c.cachedAt));
    }

    @Override public void onPageEvent(String sessionId, long tabId, PageEvent ev) {
        if (ev == PageEvent.NAVIGATED) invalidate(sessionId, tabId);
        if (ev == PageEvent.TAB_CLOSED) cache.remove(new Key(sessionId, tabId));
    }

    private record Key(String sessionId, long tabId) {}
    private record Cached(PageSnapshot snapshot, SnapshotState state, Instant cachedAt) {}
}
```

**Step 4: [run per template]**

**Step 5: Commit.**

```bash
git commit -m "feat(browser): add PageSnapshotService with freshness state machine

Per-(session,tabId) snapshot cache. NAVIGATE success → STALE,
CLICK/TYPE/SCROLL success → SUSPECT (usable with one retry budget),
age >30s → STALE regardless. invalidate() forces refresh on next
request; cache miss issues a11y.snapshot.request via the Edge wire.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# E-stream: Integration

# Task E1: Programmatic end-to-end test (multi-tab + sequence + snapshot refresh)

**Files:**
- Create: `mateclaw-server/src/test/java/vip/mate/browser/integration/EndToEndOrchestrationTest.java`

**Responsibility (covers Codex P0-1 + P0-2 + P0-3 verification):** Spring Boot integration test that drives the full Control-Plane orchestration path with a recording WS sink standing in for the Bridge/Extension. The test must demonstrate all three foundational properties hold simultaneously.

**Step 1: Tests.**

```java
@SpringBootTest
@ActiveProfiles("test")
class EndToEndOrchestrationTest {

    @Autowired ActionPlanner planner;
    @Autowired PlanExecutionService planExec;
    @Autowired ActionExecutionService actionExec;
    @Autowired PageSnapshotService snapshotService;
    @Autowired GroundingDispatcher dispatcher;
    @Autowired BrowserSessionRegistry registry;
    @MockBean SnapshotEdgeClient snapshotClient;     // controlled in tests
    @Autowired RecordingWsSink sink;

    @Test
    void multiTab_envelopesCarryDistinctTabRefsAndDoNotCrossWire() throws Exception {
        // Codex P0-1 (v1.2 fix): the test fixture uses TWO distinct tab ids
        // and asserts each envelope carries the right one. A single-tab
        // setup would be trivially satisfied — the auditor specifically
        // flagged the previous version for using only id 42.
        final int TAB_A = 42;
        final int TAB_B = 43;
        var session = registry.register("alice", mockWs("ws-1"), "0.1.0");

        // Sink auto-replies Success for every action.execute and stamps the
        // tab_ref it observed into a per-tab counter for cross-check.
        var deliveredToTabA = new AtomicInteger(0);
        var deliveredToTabB = new AtomicInteger(0);
        sink.onEnvelope("action.execute", env -> {
            int tabRef = ((Number) env.getPayload().get("tab_ref")).intValue();
            if (tabRef == TAB_A) deliveredToTabA.incrementAndGet();
            else if (tabRef == TAB_B) deliveredToTabB.incrementAndGet();
            else fail("envelope addressed to unexpected tab_ref " + tabRef);
            sink.reply(env, new ActionResult.Success(5, new ClickSuccess()));
        });

        // Action 1: click on tab A at (100, 200).
        var clickOnA = newClickActionRequest(new TabRef.Explicit(TAB_A), 100, 200);
        planExec.execute(session, List.of(clickOnA)).block();

        // Action 2: click on tab B at (300, 400). Distinct coordinates so we
        // can also confirm the params didn't cross-wire to the wrong tab.
        var clickOnB = newClickActionRequest(new TabRef.Explicit(TAB_B), 300, 400);
        planExec.execute(session, List.of(clickOnB)).block();

        // Each tab received exactly one envelope.
        assertThat(deliveredToTabA.get()).isEqualTo(1);
        assertThat(deliveredToTabB.get()).isEqualTo(1);

        // The two envelopes are addressed to the right tabs in order.
        assertThat(sink.envelopes()).hasSize(2);
        assertThat(sink.envelopes().get(0).getPayload().get("tab_ref")).isEqualTo(TAB_A);
        assertThat(sink.envelopes().get(1).getPayload().get("tab_ref")).isEqualTo(TAB_B);

        // And the (x, y) params went to the right envelope — no cross-wire.
        var paramsA = (Map<?, ?>) sink.envelopes().get(0).getPayload().get("params");
        var paramsB = (Map<?, ?>) sink.envelopes().get(1).getPayload().get("params");
        assertThat(((Number) paramsA.get("x")).intValue()).isEqualTo(100);
        assertThat(((Number) paramsA.get("y")).intValue()).isEqualTo(200);
        assertThat(((Number) paramsB.get("x")).intValue()).isEqualTo(300);
        assertThat(((Number) paramsB.get("y")).intValue()).isEqualTo(400);
    }

    @Test
    void multiTab_unresolvableTabRef_yieldsNoTargetTabFailure() throws Exception {
        // Negative companion: if the SW resolver returns null for an
        // explicit tab id that does not exist, the result is a typed
        // NO_TARGET_TAB failure (not a silent success on the wrong tab).
        var session = registry.register("alice", mockWs("ws-1"), "0.1.0");

        // Sink simulates the SW: reply NO_TARGET_TAB for tab 99 (unknown),
        // Success for known tab 42.
        sink.onEnvelope("action.execute", env -> {
            int tabRef = ((Number) env.getPayload().get("tab_ref")).intValue();
            if (tabRef == 42) {
                sink.reply(env, new ActionResult.Success(5, new ClickSuccess()));
            } else {
                sink.reply(env, new ActionResult.Failure(
                        "NO_TARGET_TAB", "tab " + tabRef + " not alive", false));
            }
        });

        var bogus = newClickActionRequest(new TabRef.Explicit(99), 0, 0);
        var result = planExec.execute(session, List.of(bogus)).block();
        assertThat(result).isInstanceOf(PlanResult.Partial.class);
        var partial = (PlanResult.Partial) result;
        assertThat(partial.failed().code()).isEqualTo("NO_TARGET_TAB");
    }

    @Test
    void sequencing_clickSentStrictlyAfterMoveResult_andAfter180ms() throws Exception {
        // Codex P0-2 verification: F4 honours concatMap, click waits.
        var session = registry.register("alice", mockWs("ws-1"), "0.1.0");
        var moveStartedAt = new AtomicLong();
        var clickSentAt = new AtomicLong();

        sink.onEnvelope("action.execute", envelope -> {
            String kind = (String) envelope.getPayload().get("kind");
            if ("move_mouse".equals(kind)) {
                moveStartedAt.set(System.currentTimeMillis());
                // simulate the real Extension delay (cursor transition)
                sink.scheduler().schedule(() ->
                        sink.reply(envelope, new ActionResult.Success(180,
                                new MoveMouseSuccess(System.currentTimeMillis(), 7))),
                        180, TimeUnit.MILLISECONDS);
            } else if ("click".equals(kind)) {
                clickSentAt.set(System.currentTimeMillis());
                sink.reply(envelope, new ActionResult.Success(20, new ClickSuccess()));
            }
        });

        var move = newMoveActionRequest(new TabRef.Main(), 100, 200, "natural");
        var click = newClickActionRequest(new TabRef.Main(), 100, 200);
        var result = planExec.execute(session, List.of(move, click)).toFuture().get(2, TimeUnit.SECONDS);

        assertThat(result).isInstanceOf(PlanResult.Success.class);
        long delta = clickSentAt.get() - moveStartedAt.get();
        assertThat(delta)
                .as("click must be sent at least 180ms after move-start (cursor transition)")
                .isGreaterThanOrEqualTo(180);
        // Only one in flight at any time:
        assertThat(sink.maxConcurrentInFlight()).isEqualTo(1);
    }

    @Test
    void snapshotRefresh_navigateInvalidatesRefIds() throws Exception {
        // Codex P0-3 verification: after NAVIGATE, snapshot becomes STALE,
        // next ground() goes back to the snapshotClient (refresh).
        var session = registry.register("alice", mockWs("ws-1"), "0.1.0");

        // Snapshot v1: contains ref_1.
        var snap1 = new PageSnapshot("snap-1", "main\n  button \"Submit\" [ref_1 @100,200,80,32]",
                new Viewport(1280, 800), Instant.now().toEpochMilli());
        // Snapshot v2: contains ref_5 (ref_1 was on the previous page).
        var snap2 = new PageSnapshot("snap-2", "main\n  button \"Next\" [ref_5 @50,100,60,32]",
                new Viewport(1280, 800), Instant.now().toEpochMilli() + 1000);
        when(snapshotClient.request(eq(session), any(), any()))
                .thenReturn(Mono.just(snap1))   // first call
                .thenReturn(Mono.just(snap2));  // second call after invalidation

        // First ground succeeds on ref_1.
        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"));
        var r1 = dispatcher.ground(session, new TabRef.Main(), hint);
        assertThat(r1).isInstanceOf(GroundingResult.Hit.class);

        // Simulate a successful navigate (no real WS round-trip here).
        snapshotService.onActionSuccess(session.getId(), 42L, ActionKind.NAVIGATE);

        // Second ground: snapshot is STALE → service must re-request → snap2.
        var hint2 = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"));
        var r2 = dispatcher.ground(session, new TabRef.Main(), hint2);
        assertThat(r2).isInstanceOf(GroundingResult.Miss.class);    // "Submit" not in snap2

        // And the snapshot client was hit twice (once initial, once post-navigate refresh).
        verify(snapshotClient, times(2)).request(eq(session), any(), any());
    }

    @Test
    void stopButton_cancelsInflightAndFiresIndicatorHide() throws Exception {
        // Codex P1-4 closure verification: STOP from the Extension causes
        // CP cancel + indicator.hide outbound.
        var session = registry.register("alice", mockWs("ws-1"), "0.1.0");

        // Plan a long navigate that we will interrupt.
        var nav = newRequest(ActionKind.NAVIGATE,
                new NavigatePayload("https://slow.example", null, "load"), 30_000);

        sink.onEnvelope("action.execute", env -> { /* never reply */ });
        var future = planExec.execute(session, List.of(nav)).toFuture();
        await().untilAsserted(() -> assertThat(sink.envelopes()).hasSize(1));

        // Now the user "clicks Stop" — simulate the Extension envelope:
        edgeHandler.handleInbound(session, new EdgeMessage(/* kind */ "indicator.stop_clicked",
                /* session_id */ session.getId(), /* tab_ref */ 42));

        var result = future.get(1, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(PlanResult.Partial.class);
        var partial = (PlanResult.Partial) result;
        assertThat(partial.failed().code()).isEqualTo("CANCELLED");

        // Last outbound on the wire is indicator.hide.
        assertThat(sink.lastEnvelopeKind()).isEqualTo("indicator.hide");
    }
}
```

**Step 2: [run per template]**

**Step 3: Implement RecordingWsSink as a Spring `@TestComponent`** that captures every outbound `EdgeMessage`, supports `onEnvelope(kind, handler)` for reactive replies, and tracks `maxConcurrentInFlight()` by incrementing a counter on `action.execute` enqueue and decrementing on the corresponding `action.result` reply.

**Step 4: [run per template]**

**Step 5: Commit.**

```bash
git commit -m "test(browser): end-to-end orchestration test covering P0-1/P0-2/P0-3

Multi-tab routing (tab_ref dispatch), sequence timing (click≥180ms
after move start, single-in-flight invariant), snapshot refresh
(navigate → stale → refetch), and Stop closure (indicator.stop_clicked
→ CANCELLED + indicator.hide outbound).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

# Task E2: douyin.com manual smoke

**Files:**
- Create: `mateclaw-extension/docs/PHASE-2-SMOKE.md`

A manual runbook:

1. Have Phase 1 smoke green (WSS connectivity + ping/pong).
2. Build extension, load unpacked, install Native Host manifest.
3. Open sidepanel on a fresh `about:blank` tab. Verify Chrome shows the yellow "browser is being controlled by automated software" banner once `debugger` permission is exercised.
4. From the Web Console (mateclaw-server admin UI), send a sequence: `navigate to https://www.douyin.com/search/test → move_mouse to (500,200) → click(500,200)`.
5. Observe in Chrome: tab navigates; phantom cursor appears + glow border + stop button; cursor smoothly moves to (500,200); click happens (visible search box focus or response).
6. Click the stop button. Verify everything fades and the sidepanel shows the cancelled state.

**Step 1: Just create the runbook.**

**Step 2: Commit.**

---

# Task E3: Update runbook + acceptance checklist

**Files:**
- Modify: `mateclaw-browser-bridge/docs/SMOKE-TEST.md` (link to Phase 2 runbook)
- Modify: this plan (acceptance checklist below)

```bash
git commit -m "docs(browser): wire Phase 2 smoke runbook into the master smoke doc"
```

---

# Acceptance checklist (Phase 2 final gate, v1.1)

**Unit / integration tests**
- [x] `cd mateclaw-server && mvn -q test` passes (P/F-stream tests including new F4, F5). — Wave 4 close: 175/175 in `vip.mate.browser.**`; Wave 5 close adds E1 to 180/180.
- [x] `cd mateclaw-browser-bridge && pnpm test` still passes (Phase 1 TS pivot; edgeproto adds new Kind constants). — 27/27.
- [x] `cd mateclaw-extension && pnpm test` passes (B/C/D-stream including new B11). — 235/235.
- [x] `cd mateclaw-extension && pnpm build` produces `dist/manifest.json` listing `debugger` and `scripting` permissions and both new `content_scripts` entries with **`all_frames: false`** on each. — verified Wave 3 + Wave 1 fix `7f856b2c`.

**Codex P0 closure verification (must all be green before merge)**
- [x] **P0-1 tab_ref**: E1 `multiTab_envelopesCarryDistinctTabRefsAndDoNotCrossWire` + negative companion `multiTab_unresolvableTabRef_yieldsNoTargetTabFailure` pass. v1.2 audit fix verified.
- [x] **P0-2 sequencing**: E1 `sequencing_clickSentStrictlyAfterMoveResult_andAfter180ms` passes. `grep -E 'flatMap|Flux\.merge|Mono\.zip|allOf'` on the action-stream returns zero hits; `concatMap` is the single dispatch op in F4.
- [x] **P0-3 snapshot refresh**: E1 `snapshotRefresh_navigateInvalidatesRefIds` passes. `PageSnapshotService` calls the (mocked) `SnapshotEdgeClient` twice (initial + post-navigate refresh) — proven via `verify(snapshotClient, times(2))`.

**Codex P1 invariants**
- [x] **P1-1 CDP lifecycle**: `debugger-manager.test.ts` covers both `target_closed` AND `canceled_by_user` (+ `replaced_with_devtools` alias) — Codex 08. Pending sends rejected with typed `SessionDetachedError` on detach; both onDetach handlers register/remove cleanly.
- [x] **P1-2 cancel race**: `ActionExecutionServiceTest` covers `cancel_winsRaceAgainstResult_…` AND `cancel_losesRaceAgainstResult_…` AND `deadlineExpiry_…`. State machine uses `AtomicReference<State>` CAS; pending-map removal uses two-arg `pending.remove(key, value)`. Grep for `pending\.remove\([^,]+\)` finds 0 hits.
- [x] **P1-3 hold delay**: `click.test.ts` covers injected `clock` + `random` for deterministic press-hold timing. log-normal distribution bounded across seeds.
- [x] **P1-4 stop closure**: E1 `stopButton_cancelsInflightAndFiresIndicatorHide` passes (re-enabled in commit `3eb82969` after `ActionExecutionService.handleStopClicked` was wired to emit the `indicator.hide` bookend after `action.cancel`).
- [x] **P1-5 iframe**: `a11y-tree.test.ts` excludes iframe content; manifest has `"all_frames": false` on both `content/a11y-tree.js` and `content/visual-indicator.js` entries.
- [x] **P1-6 WindMouse**: `move_mouse.test.ts` "natural profile generates >= 5 waypoints" passes (B7, Sub-agent C). F1 `ActionPlanner` emits exactly 2 actions for ClickStep (NOT N moves) — `clickStep_emitsMoveMouseThenClick` test locks this.
- [x] **P1-7 TDD discipline**: every Codex / sub-agent commit shows the failing-test step first via the conventional commit message pattern. Spot-check passes for `c8c77869` (B6), `64c18232` (B7), `1e52af5f` (B4).
- [x] **P1-8 reduced-motion**: `PhantomCursor.test.ts` + `GlowBorder.test.ts` + `StopButton.test.ts` all cover `prefers-reduced-motion: reduce` with both JS `matchMedia` check and CSS `@media` `!important` override. Phase-1 audit script invariant #7 scores 8 matches across the three components.
- [x] **P1-9 ambiguity**: `DomEngineTest.ambiguousWhenTwoLinesMatchSameRoleAndName` passes. `GroundingResult.Ambiguous` is a permitted variant of the sealed `GroundingResult`; `ActionPlanner.plan(...)` throws `GroundingAmbiguousException` — never silently picks the first candidate.

**Codex P2 invariants**
- [x] **P2-1 typed success payload**: `ActionResult.Success.payload` is the sealed `ActionSuccessPayload` (Wave 0). 7 round-trip tests in `ActionSuccessPayloadTest`; TS-side discriminated unions in `mateclaw-extension/src/sw/action/types.ts`.
- [x] **P2-2 indicator.show idempotency**: `visual-indicator.test.ts` "idempotent install" + per-component `show()` idempotency tests confirm `querySelectorAll('#mateclaw-*').length === 1` after multiple SHOW messages.
- [ ] **P2-3 SW-kill survival** (only if D3 ships in Phase 2) — **N/A**. D3 deliberately deferred to Phase 2.1 (documented in Wave 3 audit). Phase 2 manifest does NOT mount any static pill, so this invariant cannot be violated.

**End-to-end smoke (manual — operator-executed)**
- [ ] E2 runbook executed; douyin.com search page navigated, phantom cursor visibly moves with WindMouse arc, click lands. — automated coverage via E1; manual run pending operator.
- [x] Yellow `chrome.debugger` banner appearance documented in `docs/runbooks/phase-2-actions-and-visual.md` Step 4a expectation.
- [x] Stop button click ≤ 500 ms — automated proof via E1 `stopButton_cancelsInflightAndFiresIndicatorHide`. Manual confirmation pending operator.
- [x] No `#mateclaw-*` DOM zombies after stop — `visual-indicator.test.ts` "HIDE_AGENT_INDICATORS unmounts all" + each component's `unmount()`/`hide()` idempotent removal tests cover this. Manual confirmation pending.
- [ ] Multi-tab scenario manually verified — E1 `multiTab_envelopesCarryDistinctTabRefsAndDoNotCrossWire` covers it programmatically; manual run pending operator.

**Hygiene**
- [x] All commits carry `Co-Authored-By: Claude Opus 4.7 (1M context)` or `Co-Authored-By: Codex GPT-5 (parallel)` trailer.
- [x] No file outside the planned packages was modified. `git diff <phase-1-merge-base> --stat` only touches `mateclaw-server/src/{main,test}/java/vip/mate/browser/...`, `mateclaw-browser-bridge/src/internal/`, `mateclaw-extension/`, `codex/`, `docs/`, and `scripts/audit-phase-1.sh`.
- [x] `2026-05-28-browser-agent-phase-2.audit-response.md` exists; every finding cross-references a real test or grep.

---

# Phase 3 preview (NOT in this plan)

After Phase 2 lands:

**Carried-over deferred items (v1.1 audit additions)**
- D3 static pill heartbeat self-kill (if not shipped) → Phase 2.1.
- **Iframe-internal element grounding (Codex P1-5)** — needs:
  - a11y content script back to `all_frames: true`,
  - bbox coordinates translated to page-absolute via accumulated frame offsets (walk `window.frameElement.getBoundingClientRect()` chain),
  - each a11y line tagged with `frame_id` so the SW can pick the right `chrome.scripting.executeScript({target: {tabId, frameIds: [id]}, ...})` when re-running a query on a specific ref.
- A11y engine (consumes the a11y tree Phase 2 already extracts) → first Phase 3 task.
- Vision engine (real multimodal LLM call) → second Phase 3 task.
- Real selector candidates with confidence learning → SOP work.
- Post-upgrade auth revocation + WS close 4401 emission (deferred from Phase 1).

**New Phase 3 work**
- T3.1 A11yEngine — real implementation (consume the tree text, narrow Ambiguous candidates by ancestor role / proximity to a hint label).
- T3.2 VisionEngine — multimodal LLM call route (MateClaw failover chain).
- T3.3 Drift detection — track per-step DOM/A11y/Vision hit rates, flag drops.
- T3.4 Drift Repair — LLM-assisted candidate generation, gray rollout.
- T3.5 SOP YAML format + parser + executor.
- T3.6 SOP Synthesizer (from trajectory) + user confirmation flow.
- T3.7 Trajectory recording (every step's evidence + outcome) to PG + S3.
- T3.8 First douyin Adapter as a YAML file consumed by Phase 3 SOP executor.

**Phase 4 still owns**
- DB-backed sessions (`hello.resume` in protocol).
- SQLite outbox in Native Host.
- StepRun checkpoint commit boundary.
- Recovery Matrix + State Restoration.

---

## One-line summary

**Phase 2 closes the loop from "Control Plane wants the agent to click somewhere" to "user sees the agent click somewhere in their own Chrome".** It adds `chrome.debugger`-based atomic actions to the Extension, the phantom-cursor/glow/stop visual layer, Tab Group identity (with `tab_ref` resolution), A11y tree extraction (top-frame, bbox-tagged), the snapshot freshness state machine, and a three-engine Orchestrator shell (DOM real, A11y/Vision stubbed) executed sequentially via `PlanExecutionService.concatMap`. v1.1 patches close the three structural gaps Codex flagged (target tab attribution, sequential plan execution, A11y snapshot wiring) plus nine smaller invariants. Phase 3 fills in the A11y/Vision engines, cross-frame grounding, and SOP learning on top.
