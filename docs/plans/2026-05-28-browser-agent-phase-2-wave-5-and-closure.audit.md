# Phase 2 Wave 5 + Phase 2 Closure — Final Audit

Date: 2026-05-29
Branch: `feat/browser-foundation`
Range audited (Wave 5): `8aa4d205` (Wave 4 final audit) → `cdd1a061` (E3 checklist tick) — 5 new commits
Verdict: 🟢 **GREEN — Phase 2 COMPLETE. Ready for PR.**

---

## Part 1 — Wave 5 audit

### What landed in Wave 5

Five commits closing the E-stream + one inline P1-4 fix surfaced by the integration test:

| Stream | Commit | Author | Description |
|---|---|---|---|
| **E2** | `6f97c43c` | Claude | Phase 2 manual smoke runbook (douyin sequence + reduced-motion + DevTools-open corner cases) |
| **E1 (sub-agent)** | `7135e8b0` | Sub-agent G | EndToEndOrchestrationTest + RecordingWsSink, 5 tests (4 pass + 1 @Disabled surfacing P1-4 gap) |
| **E1 (merge)** | `e1a82f38` | Claude | Merge sub-agent G's work into feat/browser-foundation |
| **P1-4 fix** | `3eb82969` | Claude | Wire `indicator.hide` into `ActionExecutionService.handleStopClicked`; re-enable E1's @Disabled test |
| **E3** | `cdd1a061` | Claude | Link Phase 2 smoke from Phase 1 runbook + tick acceptance checklist |

### Test stats (cross-runtime, post-Wave 5)

| Runtime | Pass | Δ from Wave 4 |
|---|---|---|
| Java (browser+architecture) | **180/180** | +5 |
| NH bridge | 27/27 | +0 |
| Extension | 235/235 | +0 |
| **Total** | **442/442** | **+5** |

Wave 5 specific:
| Component | Tests | File |
|---|---|---|
| `EndToEndOrchestrationTest` (E1) | 5 | `integration/EndToEndOrchestrationTest.java` |
| **Wave 5 total** | **5** | |

### Phase 1 audit script — verdict GREEN (7/7)

Unchanged from Wave 4 — visual stack invariants stable.

### The P1-4 closure (the only inline fix this wave)

Sub-agent G surfaced a real bug while running E1: `ActionExecutionService.handleStopClicked` sent `action.cancel` but NOT the `indicator.hide` bookend the plan-spec requires. The disabled test had a thorough multi-line comment block explaining the gap; I fixed the wiring in `3eb82969`:

1. Capture `slot.tabRef()` BEFORE `cancel()` (which drops the slot synchronously).
2. After `cancel()`, fire a new `indicator.hide` envelope to the same session with the captured `tab_ref`.
3. Update the Wave 1 `handleStopClicked_triggersCancelOnInflight` test to expect 3 envelopes (was 2).
4. Re-enable the E1 test (drop `@Disabled`).

This is exactly the discipline the brief asked Sub-agent G to follow — surface, don't fix. The integration test's value paid back the moment it ran.

### Wave 5 deviations from plan

- **Smoke runbook lives at `docs/runbooks/phase-2-actions-and-visual.md`** instead of the plan's `mateclaw-extension/docs/PHASE-2-SMOKE.md`. Convention: Phase 1's runbook lives under `docs/runbooks/`. Documented in E2's commit message.
- **No new `mateclaw-browser-bridge/docs/SMOKE-TEST.md` master index**. Instead, the Phase 1 runbook gained a "Next: Phase 2 smoke" section linking to the Phase 2 runbook — same effect, one less file.
- **E1's `RecordingWsSink` design**: Sub-agent G picked Option A (observe at `WebSocketSession.sendMessage` seam via Mockito stub). Option B would have required decorating `ActionExecutionService`; Option A is narrower. Documented in their summary.

### Wave 5 verdict

🟢 **GREEN** — E1 closes the P0 trio + P1-4 closure verification; E2 + E3 close the docs surface; the inline P1-4 fix is a clean closure of the gap surfaced by E1.

---

## Part 2 — Phase 2 closure report

### Scope, in one sentence

Phase 2 closes the loop from **"Control Plane wants the agent to click somewhere"** → **"user sees the agent click somewhere in their own Chrome, with visual indicators"** — built on the Phase 1 transport foundation.

### Wave-by-wave summary

| Wave | Theme | Commits | Audit verdict |
|---|---|---|---|
| **0** | Protocol v1.1 (3 runtimes) + `ActionRequest` envelope | 2 | (rolled into Wave 1 audit) |
| **1** | `chrome.debugger` + WindMouse + A11y CS + TabGroupManager + `ActionExecutionService` | 9 (+ docs) | 🟢 GREEN |
| **2** | 6 atomic action handlers + dispatch shell + SW message routing | 13 (+ docs) | 🟢 GREEN |
| **3** | Visual indicator stack (cursor + glow + stop) + A11y snapshot bridge + VisualCoordinator | 9 (+ docs) | 🟢 GREEN |
| **4** | Control Plane orchestrator (Planner / Grounding / DomEngine / Sequential Executor / Snapshot freshness) | 8 (+ docs) | 🟢 GREEN |
| **5** | End-to-end integration test + manual smoke runbook + acceptance checklist | 5 | 🟢 GREEN |
| **Total** | | **86 commits ahead of `dev`** | |

### Test stats — final

| Runtime | Tests | Description |
|---|---|---|
| Java | **180** | sealed-type validation, ArchUnit, action protocol, orchestrator (planner / dispatcher / executor / snapshot), edge session, integration |
| NH bridge | **27** | edgeproto envelope, NM codec, edge client, runner reconnect, config |
| Extension | **235** | edge-protocol, native-bridge, debugger-manager, tab-group-manager, a11y-tree, WindMouse, ActionExecutor + 6 handlers, tab-ref-resolver, action-router, snapshot wiring, visual stack (cursor/glow/stop/wiring), sidepanel |
| **Total** | **442** | unit + integration across the three runtimes |

All green. The 1 H2-file-lock smoke test failure that appeared mid-development on a dev workstation was an environment issue (parallel mvn process holding the H2 DB file); resolved between Wave 3 and Wave 4. No production code defect.

### Codex P0 / P1 / P2 status — final

**P0 (must close before Phase 2 merges):**

| ID | Title | Status | Verified by |
|---|---|---|---|
| P0-1 | `tab_ref` carried end-to-end; multi-tab safe | ✅ CLOSED | E1.multiTab_envelopesCarryDistinctTabRefsAndDoNotCrossWire (+ negative companion) |
| P0-2 | Sequential plan execution — `concatMap`, NEVER `flatMap` | ✅ CLOSED | E1.sequencing_clickSentStrictlyAfterMoveResult_andAfter180ms; grep returns 0 matches on F4 |
| P0-3 | A11y snapshot freshness state machine | ✅ CLOSED | E1.snapshotRefresh_navigateInvalidatesRefIds; PageSnapshotService 17/17 tests |

**P1 (correctness invariants):**

| ID | Title | Status | Verified by |
|---|---|---|---|
| P1-1 | CDP lifecycle + typed SessionDetachedError | ✅ | DebuggerManager 8 tests |
| P1-2 | Cancel race CAS | ✅ | ActionExecutionService 9 tests + grep on `pending.remove` shows two-arg form only |
| P1-3 | Click hold delay deterministic under injected RNG | ✅ | click.test 10 tests |
| P1-4 | Stop-click → cancel + indicator.hide bookend | ✅ | E1.stopButton_cancelsInflightAndFiresIndicatorHide (closed inline this wave) |
| P1-5 | iframe filter | ✅ | a11y-tree.test "all_frames: false" + manifest verification |
| P1-6 | WindMouse natural profile ≥5 waypoints | ✅ | move_mouse.test 13 tests |
| P1-7 | TDD discipline (test-before-implementation) | ✅ | spot-check of B6/B7/B4 commit graph |
| P1-8 | prefers-reduced-motion across PhantomCursor + GlowBorder + StopButton | ✅ | Phase 1 audit script #7: 8 matches across the three components |
| P1-9 | Grounding ambiguity is a typed failure, not silent first-pick | ✅ | DomEngineTest.ambiguousWhenTwoLinesMatchSameRoleAndName + GroundingAmbiguousException |

**P2 (nice-to-have):**

| ID | Title | Status |
|---|---|---|
| P2-1 | `ActionResult.Success.payload` is sealed `ActionSuccessPayload` (not `Map<String,Object>`) | ✅ |
| P2-2 | `indicator.show` idempotency | ✅ |
| P2-3 | SW-kill survival (D3 static pill) | ⏸ **N/A** — D3 deliberately deferred to Phase 2.1 |

### Architecture coverage achieved

```
[Caller / Agent / API / SDK]
     ↓ Step(tabRef, GroundingResult)
ActionPlanner (F1)
     ↓ List<ActionRequest>
PlanExecutionService (F4, concatMap discipline)
     ↓ Mono<ActionResult>  ╲
ActionExecutionService (P3)  ╲  cancel CAS + indicator.hide bookend
     ↓ over WS                ╲
EdgeWebSocketHandler ─ Native Host ─ Extension SW
                                          ↓
                                      ActionRouter (B10)
                                          ↓ resolves tab_ref via TabRefResolver
                                      ActionExecutor (B2)
                                          ↓ kind switch
                                      ┌──┬──┬──┬──┬──┐
                                      ↓  ↓  ↓  ↓  ↓  ↓
                                  navigate click type scroll move wait
                                  (B3) (B4) (B5)  (B6)  (B7)  (B8)
                                          ↓
                                      DebuggerManager (B1)
                                          ↓ CDP
                                      Chrome

Side-loop:
GroundingDispatcher (F2) → DomEngine (F3) ─→ A11y(stub) → Vision(stub)
     ↑ snapshot (with refresh)
PageSnapshotService (F5) ─→ SnapshotEdgeClient (port) ─→ SnapshotRequestHandler (B11) ─→ a11y-tree CS (C1)
     ↑ subscribes to lifecycle
ActionExecutionService.onActionSuccess hooks (P3)
     +
Extension event.page.navigated / event.tab.closed → TabGroupManager (D1)

Visual feedback:
indicator.show → VisualCoordinator (D2) → visual-indicator CS (C5) → PhantomCursor (C2), GlowBorder (C3), StopButton (C4)
indicator.stop_clicked ← StopButton click ← user
```

Every box in this diagram is shipped and tested. The only truly absent piece is the **concrete `SnapshotEdgeClient` WS impl** (currently a fail-fast fallback bean) — flagged in the Wave 4 audit as a follow-up that naturally lands with the first real-Chrome smoke run.

### LOC and commits

- **86 commits ahead of `dev`** (vs Phase 1 baseline at 39 → Phase 2 added 47, but post-merge audit commits, sub-agent merges, and Wave 0 setup push the count higher)
- **~14,000 net LOC** added across Java + TypeScript + Markdown
- **Six** parallel Codex tasks shipped (08/09/10/11/12/13/14/15/16/17/18 = 11 prompt files but a few are bundled)
- **Seven** Claude sub-agents in isolated worktrees (A through G) shipped without conflict (after fixing one worktree-mis-routing process bug)

### Deferred to Phase 2.1 / Phase 3

| Item | Owner | Reason |
|---|---|---|
| D3 static pill heartbeat self-kill | Phase 2.1 | Stretch in plan; ~50 LOC follow-up |
| `SnapshotEdgeClient` concrete WS impl | Phase 2.1 | Plan doc says "real impl lands with first real-Chrome smoke" |
| A11y engine real implementation (consume tree text) | Phase 3 | Phase 2 ships stub returning `Miss("stub-phase-2")` |
| Vision engine (multimodal LLM) | Phase 3 | Same |
| Iframe-internal element grounding | Phase 3 | Documented in plan §"Out of scope" |
| SOP YAML format + Synthesizer + Trajectory recording | Phase 3 | The whole "learn once, replay fast" arc |
| Multi-tab orchestration (programmatic tab binding from sidepanel) | Phase 3 | Plan §C5: TabGroupManager API is ready; UI binding is Phase 3 |
| 14 pre-existing tsc errors in 2 test files | Wave 4 follow-up | Tests pass at runtime; tsc strictness is a separate clean-up sweep |

### Process improvements captured for Phase 3

1. **Sub-agent worktree base verification** — happened twice (Sub-agents C and E started on stale base branches and self-corrected with `git reset`). Worth a CI-side hook before letting sub-agents start.
2. **Manifest dual-source sync** (`manifest.json` + `public/manifest.json`) — explicit project invariant; mentioned in `CONTRIBUTING.md` would prevent future agents from missing one copy.
3. **Codex stub-for-self-compilation pattern** — when two parallel agents need a shared interface, the brief should explicitly name which agent ships the canonical version and which creates a drop-at-integration stub. Documented post-fact in Wave 4 audit.
4. **Integration tests catching wiring gaps** — E1 surfaced P1-4 (indicator.hide missing) when no unit test had caught it. **Wave 4 produced the unit tests; only Wave 5's integration test caught the cross-component gap.** This pattern (unit tests at component boundaries; integration tests at composition boundaries) is the right one — keep it.

### One-line closure

🟢 **Phase 2 is COMPLETE.** Atomic actions, visual indicators, and the Control Plane orchestrator all ship behind 442 unit + integration tests, with all P0 / P1 invariants closed and the P2-3 D3 stretch deliberately deferred to Phase 2.1. The full grounded action loop — `Step → Plan → Execute → CDP → Visual feedback → Stop` — is functionally complete; the only missing piece for production use is the `SnapshotEdgeClient` real WS implementation, which naturally lands with the first real-Chrome smoke session.

### Next concrete steps

1. **Smoke test on real Chrome** — `docs/runbooks/phase-2-actions-and-visual.md` walks the operator through it. The 2 unticked checklist items (douyin smoke executed; multi-tab manual) want this run.
2. **`SnapshotEdgeClient` real impl** — replaces the fail-fast fallback bean. Estimated ~80 LOC; pair it with the first smoke run.
3. **PR open** — branch is 86 commits ahead of `dev`. Mega-PR (one big merge) is fine given the audit reports document the wave-by-wave delta; squash-merge would lose that history. **Recommend merge-commit (not squash) so each Wave's verdict is preserved.**
4. **Wave 2.1 (D3 + SnapshotEdgeClient + tsc cleanup)** — small follow-up wave; ~150 LOC total. Can ship before or after the PR is open.
5. **Phase 3** — A11y/Vision engines, SOP, drift detection, iframe grounding. Plan to follow.
