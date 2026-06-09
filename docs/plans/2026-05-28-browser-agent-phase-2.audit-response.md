# Phase 2 Audit Response

Codex audit pass: 3 P0 / 9 P1 / 3 P2 — overall verdict **REWRITE**.

The verdict triggered on the 3 P0s; the architecture itself is sound — what
the audit identified are connection-layer gaps (tab attribution, sequential
plan execution, A11y snapshot wiring). Targeted patches lift the plan from
REWRITE to NEEDS PATCH → READY without re-doing the workstream breakdown.

This document records the disposition of every finding and pins the exact
patches applied to `2026-05-28-browser-agent-phase-2.md`. Format mirrors
the Phase 1 audit response.

---

## P0-1 — `action.execute` lacks target Tab attribution

**Status: ACCEPTED. Patch applied.**

**Root cause**: payload was `{kind, params, deadline_ms}`. A `BrowserSession`
is bound to a `subject` (user), not a tab. The SW had no way to resolve
which tab to drive.

**Decision**: add `tab_ref` to every envelope that targets a tab
(`action.execute`, `indicator.*`, `a11y.snapshot.request`). Phase 2
minimum implementation:

- `tab_ref: "main"` — resolved by SW via `TabGroupManager.getMainTabId(...)`.
- `tab_ref: "active"` — fallback when no managed group exists (focused tab).
- `tab_ref: number` — explicit Chrome tab id (for testing and future
  multi-tab orchestration).

On a session with no managed group AND no active tab, the SW returns
`action.result` Failure with `code: "NO_TARGET_TAB"`.

**Patches**:
1. `docs/specs/edge-protocol.md` — payload schemas for all three kind
   families include `tab_ref` field.
2. P1 test — round-trip a `tab_ref: "main"` envelope; assert the field
   survives.
3. P2 `ActionRequest` Java record adds `TabRef tabRef` field; `TabRef` is
   a sealed interface with permitted subtypes `Main`, `Active`, `Explicit(long id)`.
4. B10 SW message wiring resolves tab_ref → tabId before invoking
   ActionExecutor; on failure emits `NO_TARGET_TAB` Failure.
5. D1 `TabGroupManager` API stays as-is; the new resolution is just one
   `getMainTabId()` call from the new wiring code.
6. D2 `VisualCoordinator.indicator(...)` accepts the same `TabRef` shape.
7. F1 `ActionPlanner` propagates the incoming step's `TabRef` onto every
   action it emits.
8. E1 integration test adds a multi-tab scenario: open two tabs, send
   `action.execute` with `tab_ref: "main"`, assert only the managed group's
   main tab received the command.

---

## P0-2 — No sequential Plan executor

**Status: ACCEPTED. New task added.**

**Root cause**: F1 produced a list of actions; P3 only ran one at a time;
no component in the plan was responsible for executing the list serially
and awaiting each result. E1's pseudocode could have been read as "send
both at once", which would have shipped a broken visual-timing contract.

**Decision**: add a new task **F4 `PlanExecutionService`** owning the loop:

```java
public Mono<PlanResult> execute(BrowserSession session, List<ActionRequest> plan) {
    return Flux.fromIterable(plan)
        .concatMap(req -> Mono.fromFuture(actionExecutionService.execute(session, req)))
        .collectList()
        .map(PlanResult::from);
}
```

(or equivalent imperative `CompletableFuture.thenCompose` chain). The point
is **`concatMap`, not `flatMap`** — sequential, not parallel.

On any step's `ActionResult.Failure`, the chain stops, the remainder of
the plan is dropped, and `PlanResult.partial(...)` carries the executed
prefix + the failed step.

**Patches**:
1. New task `F4` in the F-stream between F3 and the integration stream.
2. E1 explicit assertions:
   - At most one `action.execute` envelope is in the WS sink at any moment
     (counter via wrapping sink).
   - The click envelope's send timestamp is strictly **after** the move's
     `action.result` arrival timestamp.
   - Wall-clock from move-start to click-sent ≥ 180 ms (the cursor
     transition).
3. F1 docstring updates: "Planner produces an ordered list. Sequential
   execution is F4's job."

**Re-audit criterion**: Codex should grep for `flatMap`/`Flux.merge`
under `vip.mate.browser.orchestrator` and find none on the action-stream
path.

---

## P0-3 — A11y snapshot pipeline missing

**Status: ACCEPTED. New task + spec section added.**

**Root cause**: P1 defined `a11y.snapshot.request/response` kinds, C1
created `window.__mateclaw_a11y_tree(...)`, F3 consumed `PageSnapshot` —
but nothing in the plan **bridged** them: no SW message handler that
called the content-script function on demand, no CP-side service that
requested the snapshot, no story for when ref ids go stale.

**Decision**: add four parts.

1. **New task B11 `SnapshotRequestHandler`** (Extension SW): listens for
   `a11y.snapshot.request` envelope, resolves `tab_ref`, calls
   `chrome.scripting.executeScript({ target: { tabId }, func: () =>
   window.__mateclaw_a11y_tree(filter, depth, maxChars, refId) })`,
   wraps the result in `a11y.snapshot.response` with `captured_at_ms`
   and a new `snapshot_id` (UUID), returns via the bridge.

2. **New task F5 `PageSnapshotService`** (Control Plane): exposes
   `Mono<PageSnapshot> request(session, tabRef, filter)`. Maintains a
   per-session **freshness map**: `Map<TabRef, SnapshotState>` where
   `SnapshotState = { snapshot_id, captured_at_ms, status: FRESH | STALE }`.

3. **Staleness rules** (documented in spec + enforced by F5):
   - `action.kind = NAVIGATE` succeeds → mark the affected tab's snapshot
     STALE; next `ground()` call must request a fresh snapshot before
     using any `ref_N`.
   - `action.kind = CLICK | TYPE` succeeds → mark snapshot **suspect**
     (still usable but with one-attempt retry budget; on miss, refresh).
   - `action.kind = SCROLL` succeeds → mark suspect (visible elements
     change).
   - Snapshot older than 30s → STALE regardless of actions.

4. **GroundingDispatcher (F2) refresh hook**: before calling each engine,
   F2 asks F5 for a snapshot; if STALE, F5 transparently fetches a new one.

**Patches**:
1. New tasks **B11**, **F5** added to workstream overview.
2. F2 docstring updated.
3. Spec adds an "A11y snapshot lifecycle" section.
4. E1 adds a sub-test: navigate → old ref_N from before nav fails to
   ground; only after refresh does a new ref_N succeed.

---

## P1-1 — Debugger lifecycle tests incomplete

**Status: ACCEPTED.**

**Decision**: introduce `SessionDetachedError extends Error` (typed), and
B1's test file covers:
- `detach reason 'devtools_open'` causes subsequent commands to reject
  with `SessionDetachedError`, message includes the reason.
- `detach reason 'target_closed'` same.
- After detach, `chrome.debugger.onDetach.removeListener` AND
  `chrome.debugger.onEvent.removeListener` are both called with the
  module's own handlers — verified by asserting the mocks' removeListener
  was called with the *same function reference* originally passed in.

**Patches**: B1 test expanded; impl already removes both listeners (the
plan showed it); the test now enforces this contract.

---

## P1-2 — `cancel` and `timeout` cleanup CAS race

**Status: ACCEPTED.**

**Decision**: both `cancel` and the timeout callback in P3 use
`inflight.remove(sessionId, holder)` (the two-argument compare-and-remove
form) — only remove if the in-flight holder is still the expected one.

For `cancel(sessionId, ...)`: the API takes an optional `correlationId`
parameter:

- If `correlationId` is provided, fail-fast when the current holder's
  correlation differs (return false, log warn).
- If `correlationId` is null, still fetch the current holder, capture
  its correlation id, then attempt the compare-and-remove with that
  exact holder.

`StopButton` (C4) **must** carry the correlation id of the action it
intends to cancel. The CP receives `indicator.stop_clicked` with the
session_id (stamped by NH) and the in-reply-to of the action currently
in flight (the CP knows what's in flight by looking up
`ActionExecutionService.inflight[sessionId].correlationId`).

**Patches**:
1. P3 `cancel(String sessionId, String correlationId, String reason)`
   signature.
2. P3 timeout callback uses two-arg `remove`.
3. New P3 test: "result arrives + new action registers + late cancel
   for old correlation does not remove the new holder."
4. C4 / B10 wire `STOP_AGENT` content-script message → SW assembles
   `indicator.stop_clicked` with the current in-flight correlation id
   (SW asks CP via a new lightweight method, or — simpler — SW just
   sends `STOP` without a correlation id; CP looks up the in-flight
   itself and uses that holder).

I'll take the second route (CP-side lookup), which keeps the Extension
ignorant of correlation ids it shouldn't be tracking.

---

## P1-3 — Click hold delay not testable

**Status: ACCEPTED.**

**Decision**: every B-stream handler that uses randomness or wall clock
takes injected `Clock` (`() => number` for `now()` ms) and `Rng`
(`() => number` for uniform [0, 1)). Tests pass deterministic stubs.

**Patches**: B3–B8 signatures change to accept `{ clock, rng }` in the
handler factory. B4 test uses fake timer + deterministic RNG to assert
the hold delay is exactly `40 + rng() * 80` ms (a fixed value when
`rng = () => 0.5`).

---

## P1-4 — Stop button closure not specified

**Status: ACCEPTED. (Couples with P1-2 patch.)**

**Decision**: closure path is:

```
[user clicks stop button in content script]
  → chrome.runtime.sendMessage({ type: "STOP_AGENT" })
  → SW receives, looks up the tab's managed group → sessionId
  → SW forwards Edge envelope { kind: indicator.stop_clicked,
                                 session_id: "" /* NH stamps */,
                                 payload: { tab_ref: <tabId> } }
  → Bridge stamps session_id → CP
  → CP receives indicator.stop_clicked
  → CP calls actionExecutionService.cancel(sessionId, null, "user_stop")
  → in-flight CompletableFuture resolves Failure("CANCELLED")
  → CP calls visualCoordinator.indicator(sessionId, { kind: "hide", tab_ref })
  → indicator.hide travels back through Bridge → Extension → fades out cursor / glow / stop
```

**Patches**:
1. P1 spec kind table already lists `indicator.stop_clicked` (Ext → CP).
2. B10 wires `STOP_AGENT` content-script → outbound Edge envelope.
3. C4 `StopButton.onClick` sends `STOP_AGENT` via `chrome.runtime.sendMessage`.
4. New test in P3: `indicator.stop_clicked` → cancel called → future
   resolves Failure(CANCELLED) → indicator.hide sent.
5. E1 covers the full closure.

---

## P1-5 — iframe bbox is frame-local

**Status: ACCEPTED. Phase 2 = top frame only.**

**Decision**: Phase 2 restricts the a11y content script to the top frame
(`all_frames: false`). Iframe grounding lands in Phase 3 with the frame
offset machinery. This is the smaller correct fix; full cross-frame
support needs the bbox to be page-absolute, which means walking the
frame tree on every snapshot, which is real work.

**Patches**:
1. C1 + C6: `all_frames: false` (was `true`).
2. Plan's "Out of scope (Phase 3+)" gets a new bullet: "iframe-internal
   element grounding".
3. C1 test: when the test DOM includes an iframe whose body has a button,
   the a11y output does NOT include that button (it's in another frame).

---

## P1-6 — WindMouse consumer ambiguous

**Status: ACCEPTED. Lives in B7.**

**Decision**: F1's contract stays: `clickStep → [MOVE_MOUSE, CLICK]`.
B7 `move_mouse` gains a `profile` param: `"natural"` (the default; runs
WindMouse internally and walks waypoints with cursor updates +
inter-segment sleeps; total returns one `action.result` after the final
waypoint) or `"linear"` (single transition, current behaviour).

This means the Extension performs the visual choreography for one
`move_mouse` call rather than the planner emitting many. Simpler
contract, no change to the planner test.

**Patches**:
1. P2 `MoveMousePayload` adds `profile: "natural" | "linear"` (default "natural").
2. B7 handler: when `profile = natural`, call `windMouse(x0, y0, x1, y1, rng)`,
   for each waypoint update phantom cursor + sleep `dt_ms`; return success
   after the last waypoint.
3. B9 stays as a pure library; B7 becomes its sole consumer in Phase 2.
4. B7 test: "natural profile produces ≥ 5 cursor updates between (0,0)
   and (100,100)"; "linear profile produces exactly 1 cursor update".

---

## P1-7 — Many tasks skip explicit "see fail"

**Status: ACCEPTED.**

**Decision**: every implementation task in B/C/D/F gets the explicit
five-step structure, with concrete `Run: ...` / `Expected: ...` lines.

To avoid bloating the document, the plan adds a one-time **"TDD step
template"** prologue (with the canonical `Run` + `Expected` patterns)
and each task references it as `[per template]` for steps 2 and 4 (run
test commands) while step 1, 3, 5 stay explicit. This honours the
audit's intent without 5× plan length.

For the four tasks where steps 2/4 are non-obvious (B11, F4, F5, E1),
the steps are written out in full.

**Patches**: prologue added; B/C/D/F tasks updated.

---

## P1-8 — reduced-motion missing unit tests

**Status: ACCEPTED.**

**Decision**: C2 and C3 each add one test using
`vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true, ... } as any)`:

- **C2**: assert the rendered cursor element's computed `transition`
  duration is ≤ 30 ms when `matches: true`.
- **C3**: assert the injected `<style id="mateclaw-glow-anim">` either is
  absent OR the `animation` property on the inner div is the literal
  string `none` when `matches: true`.

**Patches**: C2/C3 test files extended; impls add the media query as
already documented in visual contract.

---

## P1-9 — DomEngine silently picks first match

**Status: ACCEPTED.**

**Decision**: `GroundingResult` adds an `Ambiguous` variant:

```java
public sealed interface GroundingResult {
    record Hit(GroundedTarget target, String evidence) implements GroundingResult {}
    record Ambiguous(List<GroundedTarget> candidates, String evidence) implements GroundingResult {}
    record Miss(String reason) implements GroundingResult {}
}
```

F2 dispatcher behaviour:
- Hit → return.
- Ambiguous → escalate to the next engine (A11y / Vision) which may
  disambiguate.
- Miss → escalate.
- All engines exhausted with Ambiguous → return `Ambiguous` to the
  planner, which surfaces a typed `GROUNDING_AMBIGUOUS` failure to the
  caller (does NOT click).

**Patches**:
1. F2 `GroundingResult` becomes a sealed interface with three variants.
2. F3 DomEngine emits Ambiguous when ≥ 2 a11y lines match the pattern.
3. New F3 test: two buttons named "Submit" → Ambiguous with 2 candidates.
4. F1 ActionPlanner: when given an Ambiguous grounding, throws
   `GroundingAmbiguousException` rather than emitting a click. The
   PlanExecutionService converts this to `ActionResult.Failure("GROUNDING_AMBIGUOUS", ...)`.

---

## P2-1 — Untyped success payload

**Status: ACCEPTED.**

**Decision**: `ActionResult.Success` gets a sealed `ActionSuccessPayload`
type, one record per `ActionKind`:

```java
public sealed interface ActionSuccessPayload permits
    NavigateSuccess, ClickSuccess, TypeSuccess, ScrollSuccess,
    MoveMouseSuccess, WaitSuccess {}

public record NavigateSuccess(String finalUrl, int httpStatus, long elapsedMs) implements ActionSuccessPayload {}
public record ClickSuccess(long elapsedMs) implements ActionSuccessPayload {}
public record TypeSuccess(int charsTyped, long elapsedMs) implements ActionSuccessPayload {}
// ...
```

TS side mirrors as a discriminated union keyed on the action kind that
generated it.

**Patches**: P2 adds these records; B-stream handlers return the typed
shapes; spec adds per-kind success schemas; round-trip tests added.

---

## P2-2 — indicator.show idempotency not tested

**Status: ACCEPTED.**

**Decision**: C5 test "double SHOW_AGENT_INDICATORS does not duplicate
DOM" — after two consecutive show messages, `document.querySelectorAll('#mateclaw-phantom-cursor')`
returns length 1, same for glow border and stop button.

Each visual component (C2, C3, C4) already has internal idempotency
checks per the plan; this test enforces them at the wiring level.

**Patches**: C5 test added.

---

## P2-3 — D3 SW-kill test not mechanical

**Status: ACCEPTED. Phase 2 keeps D3 as stretch, formalises test.**

**Decision**: if D3 ships in Phase 2, its test must include:

1. Mount the pill with VisualCoordinator A.
2. Simulate "SW restart" by constructing a fresh VisualCoordinator B
   with empty `chrome.storage.session` (and an empty TabGroupManager).
3. Fire a `STATIC_INDICATOR_HEARTBEAT` from the same tab id.
4. Assert VisualCoordinator B responds `{ success: false }` (no managed
   group meta).
5. Pill content script's self-kill timer triggers; `document.getElementById('mateclaw-static-pill')`
   becomes null within 1.1 × heartbeat interval.

If D3 deferred to Phase 2.1, the Phase 2 acceptance checklist
explicitly removes the SW-kill survival requirement.

**Patches**: D3 task expanded with the exact 5-step test; Phase 2
acceptance checklist updated.

---

## Patches applied to the plan

Diff summary by section:

```
plan header                  revision history bumped to v1.1
spec edge-protocol.md        + tab_ref everywhere, + a11y lifecycle section
                             + per-kind success schemas
TDD step template prologue   NEW
Task P1                       tab_ref in all relevant payloads
Task P2                       sealed TabRef, sealed ActionSuccessPayload
Task P3                       cancel(sessionId, correlationId, reason),
                             two-arg remove, late-cancel race test
                             indicator.stop_clicked handler
Task B1                       SessionDetachedError + onEvent listener test
                             devtools_open detach reason
Task B3-B8                    Step 2/4 spelled out; clock/RNG injection;
                             P1-3 hold delay test
Task B7                       WindMouse consumed here (profile=natural);
                             linear profile retained as escape hatch
Task B9                       library-only; cite B7 as consumer
Task B10                      tab_ref resolution; STOP_AGENT → outbound
Task B11                      NEW: a11y.snapshot.request handler
Task C1                       all_frames: false (top frame only)
                             iframe negative test
Task C2                       prefers-reduced-motion unit test
Task C3                       prefers-reduced-motion unit test
Task C4                       Stop sends chrome.runtime STOP_AGENT
Task C5                       double-show idempotency test
Task C6                       manifest content_scripts a11y all_frames: false
Task D1                       (no change beyond confirming getMainTabId)
Task D2                       VisualCoordinator accepts TabRef
Task D3                       full 5-step SW-kill test or defer w/ note
Task F1                       propagates TabRef; throws on Ambiguous
Task F2                       GroundingResult sealed + dispatcher refresh hook
Task F3                       Ambiguous variant + ambiguity test
Task F4                       NEW: PlanExecutionService (sequential)
Task F5                       NEW: PageSnapshotService + freshness map
Task E1                       multi-tab + sequence-timing + snapshot-refresh
Acceptance checklist          mirrors the 14 findings as verifiable items
Phase 3 preview               iframe grounding moved here from Phase 2
```

After these patches, a second Codex audit should focus on:

1. **P0-1 verification**: the multi-tab test in E1 actually proves
   different tabs receive different commands (not just "the test passes
   because the only tab is the main tab").
2. **P0-2 verification**: grep for `Mono.zip`/`Flux.merge`/`Promise.all`
   on the action stream and find none.
3. **P0-3 verification**: navigate → STALE → refresh round-trip is
   visible in trace logs of E1.
4. **P1-2 verification**: the new race test in P3 actually fails on the
   one-arg `remove` and passes on the two-arg `remove`. Codex should
   try the broken impl and confirm.

---

## v1.2 — Round-2 verification remediation

Codex round-2 verdict was **REGRESSED** on two checks: V1 (P0-1) and D6.
Both patched; plan bumped to v1.2.

### V1 follow-up — multi-tab test was trivially satisfied

**Codex finding**: the `multiTab_actionExecuteLandsOnlyOnResolvedTab`
fixture used a single tab id (42). The "two tabs" language was a comment
only; the test would have passed even if dispatch ignored `tab_ref`
entirely.

**Patch applied**:
- Renamed to `multiTab_envelopesCarryDistinctTabRefsAndDoNotCrossWire`.
- Test now uses two concrete ids (`TAB_A = 42`, `TAB_B = 43`).
- Sink counts deliveries per tab; both counters must end at 1.
- Envelope `tab_ref` field is asserted explicitly for each call (42 then 43).
- Cross-wire check: the click on tab A was at `(100, 200)`; the click on
  tab B was at `(300, 400)`. The envelopes' `params` are asserted to
  match — proving the planner did not swap coordinates between tabs.
- Added a **negative companion** `multiTab_unresolvableTabRef_yieldsNoTargetTabFailure`:
  asks for `TabRef.Explicit(99)`, asserts the resulting `PlanResult.Partial`
  carries `code = "NO_TARGET_TAB"`. This pins the failure-mode contract
  the B10 / B11 handlers also test.

### D6 follow-up — iframe scope cut not in the auditor's named location

**Codex finding**: `Out of scope (Phase 3+)` list at the top of the plan
did not mention iframe grounding. The deferral appeared only in the
"Phase 3 preview" at the bottom, which is the wrong location per
audit-prompt-v2 D6.

**Patch applied**:
- Added `Iframe-internal element grounding` as the first bullet in the
  top-level "Out of scope (deferred to Phase 3+)" list, with explicit
  reference to Codex P1-5 and a one-sentence rationale (frame-offset
  bbox translation + per-frame ref tagging is Phase 3 work).
- Existing entry in the Phase 3 preview stays — it's the *forward* link
  describing what Phase 3 will do; this new top-list bullet is the
  *deferral* declaration the auditor required.

### Re-verification readiness

Both regressions are addressed in plan v1.2. A third Codex pass should
re-run only V1 + D6:

- **V1**: confirm the test method name is now
  `multiTab_envelopesCarryDistinctTabRefsAndDoNotCrossWire`, the fixture
  has two distinct tab id constants, both counters are asserted, and the
  negative `NO_TARGET_TAB` companion exists.
- **D6**: confirm the top-level "Out of scope" list's first bullet is the
  iframe deferral, cross-referencing P1-5.

No other changes were made in v1.2; the rest of the plan is identical to
v1.1.
