# Phase 2 Actions and Visual — Manual Smoke Runbook

## Overview

Phase 2 builds on the Phase 1 foundation by wiring atomic browser actions (navigate / click / type / scroll / move_mouse / wait) through CDP and a visual indicator stack (phantom cursor + glow border + stop button). The Control Plane (Spring Boot Java) plans and executes action sequences serially; the Chrome MV3 Extension translates them into CDP / `chrome.*` calls; visual indicators give the user feedback that an agent is in control of the tab.

This runbook is a **manual end-to-end smoke** on `https://www.douyin.com/`. It assumes Phase 1's runbook (`docs/runbooks/phase-1-foundation.md`) is green — i.e. the WSS handshake, NM bridge, and ping/pong all work on your machine. If Phase 1 smoke fails, fix that first; Phase 2 cannot smoke without it.

The automated end-to-end orchestration test (`EndToEndOrchestrationTest`) covers the P0 invariants in isolation under `mvn test`; this runbook adds the real-Chrome confirmation that the wire actually produces visible behaviour. They are complementary, not redundant.

## Prerequisites

| Dependency | Minimum version |
|---|---|
| Everything from Phase 1 runbook | — |
| Chrome | 116+ (MV3 sidepanel + chrome.debugger API) |
| A clean Chrome profile | recommended — DevTools open mid-test will detach the debugger and cause `DEVTOOLS_OPEN` failures (this is correct behaviour, but confusing in a first smoke) |
| 10 minutes of focused time | this is a manual visual-confirmation test |

## Step 1 — Phase 1 smoke green

From the project root:

```bash
mvn -pl mateclaw-server spring-boot:run
```

In another shell, start the Native Host (or skip; the Extension will auto-connect via `chrome.runtime.connectNative` after the install scripts have written the manifest).

Open Chrome, open the sidepanel, click "Ping". The "Pong" reply should arrive within ~100 ms. If it does not, **stop**; fix Phase 1 first.

## Step 2 — Confirm Phase 2 permissions and content scripts

In Chrome, navigate to `chrome://extensions` → **MateClaw Browser Agent (dev)** → **Details**. Confirm:

- **Permissions** include `debugger`, `scripting`, `webNavigation`, plus the Phase 1 set (`sidePanel`, `storage`, `alarms`, `notifications`, `nativeMessaging`).
- **Site access** = "On all sites".
- Inspect `chrome://extensions` → **service worker** → console — no errors during startup.

Open `chrome://inspect/#service-workers` and confirm the MateClaw SW is **active**, not "stopped".

## Step 3 — Open the test surface

1. Open a new tab on `about:blank`.
2. Open the MateClaw sidepanel for that tab. Wait for the connection indicator to show **Connected (sess-<UUID>)** in the sidepanel header.
3. From the Control Plane admin UI (`http://localhost:18088/`), navigate to **Settings → System → Browser Agent (dev)** (or whichever console surface the project exposes for direct action dispatch — in absence of a UI, see Step 3a).

### Step 3a — Direct action dispatch via HTTP

If the admin UI does not yet expose action dispatch (Phase 3 work), use `curl` to POST an `action.execute` envelope through the existing `BrowserSessionDebugController`:

```bash
# Adjust port + auth to match your local config.
curl -s -X POST http://localhost:18088/api/v1/browser/debug/dispatch \
     -H 'Content-Type: application/json' \
     -H 'Authorization: Bearer <JWT or PAT>' \
     -d '{
       "session_id": "<the session_id from the sidepanel>",
       "kind": "action.execute",
       "payload": {
         "msg_id": "smoke-1",
         "tab_ref": "main",
         "kind": "navigate",
         "params": { "url": "https://www.douyin.com/", "wait_for": "load" },
         "deadline_ms": 30000
       }
     }'
```

(If this endpoint does not exist yet, smoke through the integration test fixture or write a temporary harness — that is in scope for Wave 5 follow-up, not for this runbook.)

## Step 4 — Run the action sequence

Execute the following four envelopes in order. After each, **stop and visually confirm** the expected behaviour before sending the next.

### 4a — navigate to douyin

```json
{ "kind": "action.execute", "payload": {
    "msg_id": "smoke-1", "tab_ref": "main", "kind": "navigate",
    "params": { "url": "https://www.douyin.com/", "wait_for": "load" },
    "deadline_ms": 30000
}}
```

**Expected**:
- The tab navigates. Chrome's "browser is being controlled by automated software" banner appears (this is normal — caused by `chrome.debugger.attach()`).
- The Control Plane returns `action.result.ok=true` with `final_url` echoing the Douyin URL.

### 4b — show visual indicators

```json
{ "kind": "indicator.show", "payload": { "tab_ref": "main" } }
```

**Expected**:
- A subtle blue glow appears around the viewport.
- A **Stop Agent** pill appears bottom-center, sliding in from below.
- A small synthetic cursor (blue arrow with soft glow) appears at the top-left of the viewport (or wherever the last move_mouse left it). The cursor is **not** the OS cursor — it's a synthetic overlay.

### 4c — move the phantom cursor

```json
{ "kind": "indicator.cursor", "payload": { "tab_ref": "main", "x": 500, "y": 200 } }
```

**Expected**:
- The phantom cursor smoothly transitions to (500, 200) over ~180 ms.
- The Control Plane returns `action.result.ok=true` with `arrived_at_ms` in the payload.

### 4d — click at the new cursor position

```json
{ "kind": "action.execute", "payload": {
    "msg_id": "smoke-2", "tab_ref": "main", "kind": "click",
    "params": { "x": 500, "y": 200, "button": "left", "click_count": 1 },
    "deadline_ms": 5000
}}
```

**Expected**:
- The page responds to a click at (500, 200) — likely focuses the search input or opens a menu, depending on Douyin's current layout.
- The Control Plane returns `action.result.ok=true`.

## Step 5 — Stop button

Click the **Stop Agent** pill at the bottom of the viewport.

**Expected**:
- Phantom cursor + glow border + stop button all fade out within ~300 ms.
- The sidepanel shows the cancelled state (last action ended with `CANCELLED`).
- The Control Plane logs an inbound `indicator.stop_clicked` envelope, followed by an outbound `indicator.hide` envelope.

If a plan was in-flight when Stop was clicked, the in-flight action returns `Failure(code=CANCELLED, retryable=false)`. Any remaining steps in the plan are never sent.

## Step 6 — DevTools-open detection (optional but recommended)

1. Re-show indicators (Step 4b).
2. Open Chrome DevTools (F12) on the active tab.
3. **Expected**: the next `action.execute` returns `Failure(code=DEVTOOLS_OPEN, retryable=false)`. The debugger session detached the moment DevTools attached; the typed error tells the orchestrator to escalate rather than retry blindly.

This corner case is the Codex P1-1 invariant — `SessionDetachedError("canceled_by_user")` must propagate as `DEVTOOLS_OPEN`. If you see `HANDLER_ERROR` instead, the typed-error chain is broken; file a regression.

## Step 7 — Reduced-motion check (a11y verification)

1. macOS: System Settings → Accessibility → Display → **Reduce motion** = ON. Windows: Settings → Accessibility → Visual effects → **Animation effects** = OFF. (Restart Chrome.)
2. Re-run Steps 4b + 4c + 4d.
3. **Expected**: Phantom cursor still moves to (500, 200) but the transition is functionally instant (~30 ms). Glow border has no pulse animation. Stop button appears without sliding.

This verifies the Codex P1-8 invariant (`@media (prefers-reduced-motion: reduce)`) is honoured by all three visual components.

## Common failure modes

| Symptom | Likely cause | Fix |
|---|---|---|
| `NO_TARGET_TAB` on a `tab_ref: "main"` call | No tab has been bound to "main" for this session | Either set it programmatically via the sidepanel or fall back to `tab_ref: "active"` for the smoke |
| Phantom cursor jumps to (0,0) instead of smooth transition | Reduced-motion is enabled or matchMedia is reporting `reduce` | Confirm Step 7 expectation; this is correct behaviour |
| `DEVTOOLS_OPEN` on the first action | DevTools was open when the test started | Close DevTools, re-attach via a fresh navigate |
| Sidepanel says "Disconnected" mid-test | Native Host crashed or the SW was idle-evicted | Check `mateclaw-server/logs/mateclaw.log` for `4408 heartbeat-timeout`; reload the extension |
| Click at (500,200) lands at a different visual position | Page zoom ≠ 100% or CDP viewport mismatch | Reset zoom to 100% (Ctrl+0) and retry |

## What this runbook does NOT cover

- **Programmatic verification** of the P0 invariants — that is `EndToEndOrchestrationTest` (E1). Run that under `mvn test` to confirm sequencing / multi-tab / snapshot-refresh in isolation.
- **Multi-tab routing on real Chrome** — covered by E1's fixture; manual confirmation would need a second managed tab and the admin UI's tab-binding controls (Phase 3).
- **Long-running plans** with multiple atomic actions composed via the planner — possible to construct manually but tedious; see E1 for the canonical sequence test.
- **Network failures, slow networks** — not in scope for the smoke. The Phase 2 deadline / retryable contract is exercised by unit tests in F4 + P3.

## Outcome

After running Steps 1–7 with all "Expected" boxes ticked, Phase 2 is **smoke-green on your machine**. Report any deviation as a regression issue and re-run the affected step.
