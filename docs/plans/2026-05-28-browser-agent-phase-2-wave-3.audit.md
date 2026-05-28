# Phase 2 Wave 3 — Final Audit

Date: 2026-05-28
Branch: `feat/browser-foundation`
Range audited: `c8e4f4b0` (Wave 2 final audit) → `00ad87e2` (P1-8 + audit-script fix) — 8 new commits
Verdict: 🟢 **GREEN — ready to start Wave 4**

---

## What landed in Wave 3

Six task streams executed in three sub-waves:

| Sub-wave | Stream | Task | Commit | Author | LOC |
|---|---|---|---|---|---|
| **3-0 (serial)** | — | Task 0 — replace B3-B8 stubs with real handler factories in sw/index.ts | `e7b2950c` | Claude (main) | -16 net |
| 3-1 (docs) | C-docs | 2 Codex prompts (14/15) | `d870003a` | Claude | +428 |
| **3-1 (parallel)** | B | B11 — SnapshotRequestHandler (a11y.snapshot.request bridge) | `0e80c6c5` | Codex 14 | +~400 |
| 3-1 | D | D2 — VisualCoordinator (indicator.* routing) | `71d0a2fa` | Codex 15 | +414 |
| 3-1 | C | C2+C3+C4+C5 — PhantomCursor + GlowBorder + StopButton + wiring CS | `6047ad86` + merge `10492f3f` | Sub-agent E | +1,448 |
| 3-1 (polish) | D | VisualCoordinator test tightening | `4ee7cf09` | Claude | +small |
| **3-2 (audit-fix)** | C+scripts | prefers-reduced-motion coverage + audit-script test-file exclusion | `00ad87e2` | Claude (audit) | +125 |

**Total new code: ~2,800 LOC** across `src/sw/` (B11 + D2 + Wave 3 task 0) + `src/content/visual/` (C2-C4) + `src/content/visual-indicator.{ts,test.ts}` (C5).

---

## Test stats (cross-runtime, post-Wave 3)

| Runtime | Pass | Δ from Wave 2 | New tests this wave |
|---|---|---|---|
| Java (`mvn test -Dtest='vip.mate.browser.**,vip.mate.architecture.**'`) | **101/101** | +0 | 0 (Wave 3 is TS-only) |
| NH bridge (`pnpm test`) | **27/27** | +0 | 0 |
| Extension (`pnpm test`) | **235/235** | +67 | 67 |
| **Total** | **363/363** | **+67** | **+67** |

Test breakdown by Wave 3 stream:

| Component | Tests | File |
|---|---|---|
| `SnapshotRequestHandler` (B11) | ~14 | `sw/snapshot-request-handler.test.ts` |
| `VisualCoordinator` (D2) | 13 | `sw/visual-coordinator.test.ts` |
| `PhantomCursor` (C2) | 9 | `content/visual/PhantomCursor.test.ts` |
| `GlowBorder` (C3) | 9 | `content/visual/GlowBorder.test.ts` |
| `StopButton` (C4) | 11 | `content/visual/StopButton.test.ts` (+2 from P1-8 audit fix) |
| `visual-indicator` CS (C5) | 11 | `content/visual-indicator.test.ts` |
| **Wave 3 total** | **67** | |

---

## Phase 1 audit script — verdict GREEN

```
bash scripts/audit-phase-1.sh
verdict: GREEN
```

All 7 invariants now PASS (Wave 1 audit had #7 N/A; Wave 3 makes it PASS with 8 matches across the three visual components):

| # | Invariant | Status | Detail |
|---|---|---|---|
| 1 | untrusted-event ban (.click() / dispatchEvent) | ✅ PASS | 0 matches (after test-file exclusion fix) |
| 2 | session_id ownership | ✅ PASS | 0 matches |
| 3 | register CAS atomic compute | ✅ PASS | 0 matches |
| 4 | real auth APIs | ✅ PASS | 0 matches |
| 5 | single ws.on('message') reader | ✅ PASS | 1 match (correct location) |
| 6 | HeartbeatTimeoutError exported | ✅ PASS | 1 match |
| 7 | **prefers-reduced-motion in visual code** | ✅ **PASS (NEW)** | **8 matches** across PhantomCursor + GlowBorder + StopButton |

---

## ArchUnit — still 6/6 green

No server-side code in Wave 3; ArchUnit rules untouched.

---

## Defects found during Wave 3 (caught + fixed inline)

### D-W3-1: StopButton lacks prefers-reduced-motion handling (P0)

- **Severity**: P0 — accessibility violation. A user who set `prefers-reduced-motion: reduce` in their OS would still see the 300ms slide-in animation; this is a hard accessibility failure under WCAG 2.3.3.
- **Caught by**: my Wave 3 audit script run — invariant #7 went from N/A in Wave 1 to FAIL in Wave 3 with only 1 match (GlowBorder).
- **Fix** (`00ad87e2`):
  - Added JS `prefersReducedMotion()` helper mirroring GlowBorder's pattern
  - At `show()`, if reduced, skip slide-in: button starts at `translateY(0)/opacity:1` with 30ms transition
  - Injected `<style id="mateclaw-stop-button-style">` with `@media (prefers-reduced-motion: reduce) { #mateclaw-stop-button { transform: translateY(0) !important; ... } }` — CSS-level fail-safe that wins before JS runs
  - Two new vitest cases assert both layers

### D-W3-2: PhantomCursor has JS check but no CSS @media (P1)

- **Severity**: P1 — defense-in-depth gap. JS `matchMedia` can't override the initial paint frame before the SW's content script runs on a slow connection.
- **Caught by**: same audit script run.
- **Fix** (`00ad87e2`):
  - Added `ensureStyles()` injecting `<style id="mateclaw-phantom-cursor-style">` with `@media (prefers-reduced-motion: reduce)` rule that overrides the transition to 30ms with `!important`
  - Mirrors GlowBorder's existing pattern

### D-W3-3: Audit script false-positive on .click() in test files (P2)

- **Severity**: P2 — false positive only; no code defect.
- **Caught by**: audit script after D-W3-1+2 fixes — section still FAIL because invariant #1 flagged 4 `.click()` calls in `*.test.ts` files (legitimate user-click simulation in tests).
- **Fix** (`00ad87e2`):
  - `scripts/audit-phase-1.sh` invariant #1 now uses `--exclude='*.test.ts'`, matching the existing pattern on invariant #5
  - Verified all 7 invariants now PASS

---

## Wave 3 invariant audit (Codex hot-spot checks updated for visual stack)

| # | Invariant | Status | Evidence |
|---|---|---|---|
| 1 | All three visual components honour `@media (prefers-reduced-motion: reduce)` | ✅ PASS | grep finds 8 matches across PhantomCursor + GlowBorder + StopButton (both CSS injection and JS matchMedia paths) |
| 2 | Visual components inject ONE stylesheet per page (idempotent re-mount) | ✅ PASS | Each has a unique `STYLE_ID` constant and `if (document.getElementById(STYLE_ID)) return` guard |
| 3 | z-index hierarchy: cursor < (glow == stop), stop must be clickable | ✅ PASS | `PhantomCursor` z=2147483646; `GlowBorder` + `StopButton` both 2147483647; stop appended later in DOM order |
| 4 | StopButton supports `suppressed=true` (MCP mode) — no DOM mount | ✅ PASS | dedicated test "show({ suppressed: true }) does NOT mount anything (MCP mode)" |
| 5 | `visual-indicator.ts` is idempotent — re-injection-safe content script | ✅ PASS | `if (window.__mateclaw_visual_indicator_installed) return` guard matches a11y-tree's pattern |
| 6 | All outbound STOP_AGENT envelopes carry `session_id: ""` (Phase 1 P0-1 preserved) | ✅ PASS | CS sends `{ type: 'STOP_AGENT' }` only; SW (`sw/index.ts`) stamps tab_ref from `sender.tab.id` and wraps via `makeEdgeMessage` which sets session_id="" |
| 7 | `SnapshotRequestHandler` single-round-trip: returns tree + viewport in one `chrome.scripting.executeScript` | ✅ PASS | dedicated test verifies one executeScript call returning both fields |
| 8 | `VisualCoordinator.handles(kind)` static predicate matches exactly the 5 CP→Ext indicator.* kinds | ✅ PASS | dedicated test "handles returns true for the 5 indicator.* kinds and false for action.execute / a11y.snapshot.request" |
| 9 | `indicator.cursor` round-trips with `action.result` envelope upstream | ✅ PASS | dedicated test verifies coordinator emits action.result with in_reply_to=request.msg_id after sendMessage response |
| 10 | `chrome.tabs.sendMessage` rejection (tab gone) does NOT throw out of the coordinator | ✅ PASS | dedicated test verifies the catch-and-log path |
| 11 | `NO_TARGET_TAB` returned from B11 snapshot handler without invoking executeScript (defense in depth) | ✅ PASS | dedicated test verifies executeScript never called when resolution fails |
| 12 | A11y snapshot lifecycle: response carries `tab_ref` = resolved tab id (echoed for freshness-map keying) | ✅ PASS | matches docs/specs/edge-protocol.md §"a11y.snapshot.response" |

---

## Code quality spot-checks

### `SnapshotRequestHandler` (B11) — Codex 14

- Single executeScript call returning `{ tree, viewport }` — no double round-trip.
- `chrome.scripting.executeScript` rejection caught and surfaced as `SNAPSHOT_FAILED` typed response (not a raw throw).
- snapshot_id from injectable UUID factory; clock injectable for `captured_at_ms`.
- `tab_ref` propagated through resolution → response (so CP's freshness map can key on the resolved tab id).
- `in_reply_to + trace_id` preserved from request.
- `session_id: ""` on outbound (P0-1 invariant).

### `VisualCoordinator` (D2) — Codex 15

- Clean dispatch via `VisualCoordinator.handles(kind)` static predicate — SW `index.ts` uses this to route, no if/else stack.
- `indicator.cursor` round-trips via `chrome.tabs.sendMessage` response → `action.result` envelope upstream. Tested explicitly.
- Unresolvable tab_ref logs warning + drops envelope silently (no chrome.tabs.sendMessage call). Tested.
- `chrome.tabs.sendMessage` rejection (tab gone) caught and logged, does not throw out.

### `PhantomCursor` (C2) — Sub-agent E

- Two SVG icons (plain + styled) co-mounted; only display flips — no layout thrash on theme change.
- `move()` returns Promise that resolves on `transitionend` OR 220ms fallback (covers happy-dom + background-tab transitionend suppression).
- Transform-origin pinned at `0,0` so the arrow tip IS the (x,y) — not the SVG's geometric center.
- `prefersReducedMotion()` + the CSS @media injection (added in audit) — both layers.

### `GlowBorder` (C3) — Sub-agent E

- State machine `HIDDEN → SHOWING → VISIBLE → HIDING → HIDDEN` cleanly handles `hide()` during SHOWING.
- `@keyframes mateclaw-pulse` injected once via `<style id="mateclaw-glow-anim">`; double-injection guarded.
- CSS @media @reduced-motion override + JS matchMedia check (the gold-standard pattern that I propagated to C2/C4 in the audit fix).

### `StopButton` (C4) — Sub-agent E + audit polish

- Bottom-center pill with brand color (`61, 117, 255`) configurable via `--mateclaw-brand-rgb` CSS variable.
- `suppressed=true` cleanly skips the mount entirely (MCP mode per research §4.3).
- z-index = max int (above glow); container has `pointer-events: none`, button itself re-enables `auto`.
- `onClick(cb)` replaces — only one consumer (the wiring CS), so the API doesn't need to be additive.
- post-audit: complete prefers-reduced-motion handling (both CSS + JS layers).

### `visual-indicator.ts` (C5) — Sub-agent E

- Document_idle top-frame only; idempotent install (re-injection-safe).
- Listens to `chrome.runtime.onMessage` from VisualCoordinator; dispatches to PhantomCursor / GlowBorder / StopButton.
- `INDICATOR_CURSOR` calls `sendResponse({ ok: true, arrived_at_ms })` — that's the round-trip D2 awaits.
- StopButton onClick → `chrome.runtime.sendMessage({ type: 'STOP_AGENT' })` (bare); SW reads `sender.tab.id` and wraps into proper `IndicatorStopClicked` envelope. This split keeps the CS free of the edge-protocol import (can't share types across isolated worlds anyway).
- `suppressStop` is sticky across TOOL_USE_HIDE/SHOW (preserves MCP suppression); resets on HIDE_AGENT_INDICATORS.

---

## What's NOT in Wave 3 (deferred)

### Deliberately deferred to Wave 4+

- **D3 (stretch): Static pill heartbeat self-kill** — SW periodically heartbeats the CS, watchdog auto-removes indicators 15s after SW death. Plan tagged stretch; not delivered. Critical-path Wave 3 work (B11 + D2 + visual stack) is complete without it. ~50 LOC follow-up when needed.
- **F1-F5** Control Plane Orchestrator (Planner / GroundingDispatcher / DomEngine / PlanExecutionService / PageSnapshotService) — Wave 4
- **E1-E3** Integration tests + Douyin smoke + runbook updates — Wave 5

### Process improvements identified for Wave 4

- **Sub-agent worktree base verification**: Sub-agent C (Wave 2) and Sub-agent E (Wave 3) both had to git-reset their worktrees when the harness allocated them on unrelated branches. The prompt now includes a precondition check, but the harness side should be hardened too. Track as a separate ticket; not a code defect.
- **Manifest dual-source sync**: `manifest.json` and `public/manifest.json` must stay in sync (vite ships the latter into dist/). Sub-agent E handled this correctly; document as a project invariant somewhere visible.
- **TS strict-mode pre-existing errors**: ~14 pre-existing tsc errors in `ActionExecutor.test.ts` and `native-bridge.test.ts` survive across waves. Tests still pass via vitest, but `tsc --noEmit` reports errors. Plan a clean-up sweep before Wave 4 — they may surface real bugs.

---

## Next steps

1. **Wave 4 dispatch**: 5 parallel Control Plane tasks become available — F1 (ActionPlanner), F2 (GroundingDispatcher with 3 engines), F3 (DomEngine), F4 (PlanExecutionService — `concatMap`!), F5 (PageSnapshotService). All Java/Spring under `vip.mate.browser.orchestrator.*`. **NB**: the audit's "no flatMap / Flux.merge on action-stream paths" invariant is now testable for the first time — Codex's re-audit will grep for these.
2. **Smoke test on real Chrome** (optional, highly recommended): the visual indicators are now fully wired. A 5-minute manual test on `about:blank` would confirm the phantom cursor + glow + stop button actually render correctly.
3. **PR preparation**: branch is now 72 commits ahead of `dev`. Phase 2 is roughly 60% complete (Waves 1-3 of 5). A natural PR boundary would be after Wave 4 — at that point the Control Plane can compose actions and the system can do something end-to-end.

---

## Verdict

🟢 **GREEN** — Wave 3 is fully integrated, 363/363 unit tests pass across 3 runtimes, Phase 1 audit script returns GREEN with all 7 invariants (including #7 prefers-reduced-motion now scoring 8 matches), ArchUnit untouched, no plan-vs-impl drift, three inline defects caught and fixed (one P0 accessibility, one P1 defense-in-depth, one audit-script false-positive). The visual indicator stack — phantom cursor + glow border + stop button + the SW/CS bridge layers — is wired end-to-end through D2 VisualCoordinator and the existing NM bridge. Wave 4 cleared to start.
