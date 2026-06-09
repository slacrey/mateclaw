# Phase 2 Audit Prompt for Codex

> Hand this to Codex (or any independent reviewer) along with the documents
> listed in "Required reading". Codex's output should match the format in
> the "Expected output" section so we can apply patches mechanically — same
> shape as the Phase 1 audit response.

---

## Context (for Codex, no prior session)

MateClaw is a Java + Spring Boot agent platform we're extending with an
end-to-end browser agent. Phase 1 already shipped a transport-layer
foundation: a Go Native Host bridges a Chrome MV3 Extension to the
mateclaw-server Control Plane over Bearer-WSS, with a typed `EdgeMessage`
envelope and an in-memory `BrowserSessionRegistry`. Phase 1 was already
audited (see `2026-05-28-browser-agent-foundation.audit-response.md`)
and its findings landed in the plan as v1.1.

Phase 2 adds the **actual browser control loop**:

- `chrome.debugger`-driven atomic actions (navigate/click/type/scroll/
  move_mouse/wait) — only CDP `Input.dispatchMouseEvent` / `dispatchKeyEvent`,
  never untrusted JS events.
- A visual indicator system: phantom cursor (CSS-transform animated), glow
  border (pulsing inset shadow), stop button, tab group identity.
- An A11y tree content script that exposes structured page contents with
  stable `ref_N` ids and per-element bbox.
- A Control Plane Orchestrator with a three-engine grounding dispatcher
  (DOM real, A11y/Vision stub).

Phase 2's deliverable is: user issues a high-level command → CP plans →
Bridge forwards → Extension drives the user's own Chrome → user
visually sees the phantom cursor move smoothly to the target and click.

## Required reading (in this order)

1. `docs/specs/edge-protocol.md` — the canonical wire format (Phase 1 v1.0 plus
   the Phase 2 v1.1 additions for `action.*` / `indicator.*` / `a11y.*`).
2. `docs/plans/2026-05-28-browser-agent-foundation.md` — Phase 1 plan (the
   baseline this builds on; Phase 2 inherits session-id-stamp contract,
   subject-binding rules, reconnect semantics).
3. `docs/plans/2026-05-28-browser-agent-foundation.audit-response.md` —
   prior audit findings and their resolutions.
4. `docs/research/2026-05-28-claude-chrome-visual-system.md` — research
   notes on the visual system patterns Phase 2 implements (phantom cursor,
   glow border, stop button, tab group). This is the *design rationale*
   for the C-stream and D-stream tasks; consult it when judging whether
   a Phase 2 implementation choice is sound.
5. `docs/plans/2026-05-28-browser-agent-phase-2.md` — the document under audit.

---

## What to check (9 hot-spots, in order of how badly they'll bite us)

The plan calls these out at the top, but here is each one expanded with
**concrete pass/fail criteria** so the audit can be mechanical rather than
narrative.

### Hot-spot 1: CDP lifecycle hygiene (B1)

**Pass criteria:**
- `DebuggerSession.test.ts` exercises (a) attach idempotency, (b) external
  detach triggered by `onDetach` with reason `'devtools_open'` AND with
  reason `'target_closed'`, (c) `sendCommand` after external detach
  rejects with a typed `SessionDetachedError` (not just any Error).
- The implementation removes both `onDetach` AND `onEvent` listeners on
  external detach (the plan shows both being added; missing the removal
  would leak listeners for every navigation).

**Fail trigger:** plan's `handleDetach` only removes `onDetachHandler`
but not `onEvent`. Look at the implementation skeleton carefully.

### Hot-spot 2: One-in-flight invariant (P3)

**Pass criteria:**
- `ActionExecutionService.execute` uses `putIfAbsent` (or equivalent atomic
  CAS) on the in-flight map, NOT `containsKey` + `put`.
- A concurrency test asserts that 100 threads calling `execute` for the
  same session result in 99 `IllegalStateException` and 1 in-flight
  registration.
- The `cancel` path uses `remove(sessionId, holder)` (compare-and-remove),
  not unconditional `remove(sessionId)`. Otherwise a cancel arriving after
  a result already cleaned up could remove the next action's holder.

**Fail trigger:** the plan shows `putIfAbsent` (good) but `cancel` uses
`remove(sessionId)` without comparing against the holder identity.

### Hot-spot 3: Cursor-before-click timing (F1 + E1)

**Pass criteria:**
- `ActionPlanner.plan(clickStep)` returns exactly two `ActionRequest`s:
  MOVE_MOUSE then CLICK at the same `(x, y)`.
- The Control Plane code that executes a plan calls `ActionExecutionService.execute`
  **sequentially**, awaiting each result before sending the next.
- `EndToEndPingClickTest` asserts: (a) only one `action.execute` envelope
  is in flight at a time; (b) the click envelope was sent strictly after
  the move's `action.result` was received; (c) wall-clock from move-start
  to click-sent ≥ 180 ms (the cursor transition).

**Fail trigger:** the plan describes the planner emitting both, but does
not show *which component* sequences them. If the Workflow Engine fires
them as a fan_out batch, the timing contract breaks. Find the gap.

### Hot-spot 4: Untrusted-event ban (B3–B7)

**Pass criteria:**
- No file under `mateclaw-extension/src/sw/action/` contains `.click()`,
  `dispatchEvent(new MouseEvent`, `dispatchEvent(new KeyboardEvent`, or
  `chrome.scripting.executeScript({func: ... el.click() ...})`.
- The `click` handler dispatches `mousePressed` THEN `mouseReleased` in
  sequence with a small humanised hold delay (40–120 ms uniform random)
  between them.
- The `type` handler dispatches one `keyDown` + `keyUp` per character,
  not via `Input.insertText` (which is also CDP but considered "synthetic"
  by some risk engines).

**Fail trigger:** if any task descends into "execute via content script
because CDP was complicated", flag it.

### Hot-spot 5: session_id contract (B10 + P-stream)

**Pass criteria:**
- The Extension's `action.result` envelope is constructed with
  `session_id: ""` in B10 and the test asserts this explicitly.
- The Bridge's existing `runner.pumpStdinToEdge` (Phase 1) is the only
  place that stamps the real session_id. Plan should not silently change
  this layer.
- Likewise, `STOP_AGENT` envelope sent by the stop button (C4) leaves
  the Extension with `session_id: ""`.

**Fail trigger:** any task that sets session_id explicitly in the
Extension (it has no way to know the real id; if it ever sets a value,
it must be empty string).

### Hot-spot 6: Tab attribution missing from action.execute

**THIS IS THE BUG I AM MOST WORRIED ABOUT.**

The plan defines `action.execute` payload as `{ kind, params, deadline_ms }`.
But a `BrowserSession` is bound to a user (subject), and one user can have
many tabs. **How does the Extension know which tab to drive?**

**Pass criteria:**
- `action.execute` payload includes a `tab_ref` (or `tab_id`) field, OR
- The plan specifies how the SW resolves "the target tab" from the session
  alone — e.g. "the tab the sidepanel is open on", "the main tab of the
  active managed group", or "the active tab in the focused window".
- Whichever choice is made, there is a test that exercises:
  multi-tab user → action.execute → action lands on the right tab.

**Fail trigger:** if the plan has no explicit story here, it's a P0 and
needs a new field added to the envelope (and a new test).

### Hot-spot 7: Tab Group ownership (D1)

**Pass criteria:**
- `TabGroupManager.adoptOrphanedGroup` checks `chrome.tabGroups.get(groupId).title.startsWith(MANAGED_PREFIX)` before adopting. Refusal returns an explicit `OrphanedGroupNotMateclawError`.
- Test mocks a Chrome group with title "My Research" (no prefix) → adopt
  refuses → existing meta map unchanged.
- Test with title `[MateClaw] alpha` → adopt succeeds.

**Fail trigger:** the plan describes the check but the test only covers
the success case. Audit demands the refusal case test.

### Hot-spot 8: SW kill survival (D3 stretch)

**Pass criteria (if D3 is implemented):**
- Static pill content script sends `STATIC_INDICATOR_HEARTBEAT` every 5s.
- VisualCoordinator's response logic requires BOTH (a) sender tab is in
  a managed group AND (b) the group has a live main tab.
- A test simulates SW being killed (all in-memory state lost) → heartbeat
  returns `{success: false}` after re-initialisation finds no managed
  group meta → pill self-removes.

**If D3 is deferred:** the plan must move this hot-spot to Phase 2.1
acceptance and explicitly note the gap.

### Hot-spot 9: prefers-reduced-motion

**Pass criteria:**
- Grep `@media (prefers-reduced-motion: reduce)` finds matches in both
  `GlowBorder.ts` (animation: none) and `PhantomCursor.ts` (transition
  duration shrunk).
- Test using `matchMedia` mock with `matches: true` verifies the cursor
  transition is shortened (assertable by reading the computed `transition`
  property).

**Fail trigger:** present in one component but not the other.

---

## Additional bugs to actively hunt for

Beyond the 9 hot-spots, here are specific places I suspect bugs hiding.
Please look at each:

**A. `ActionExecutionService.cancel` cleanup race**

```java
public void cancel(String sessionId, String reason) {
    var holder = inflight.remove(sessionId);          // ← unconditional remove
    if (holder == null) return;
    holder.timeoutFuture().cancel(false);
    sendActionCancel(sessionId, holder.correlationId(), reason);
    holder.future().complete(new ActionResult.Failure("CANCELLED", reason, false));
}
```

What if a result arrives, `onResult` removes the holder, and then `cancel`
gets called for the *next* action (just registered) and removes IT
because there is no identity comparison? Verify or refute.

**B. ActionResult.Success payload is untyped `Map<String, Object>`**

The plan documents `action.result` success as `{ ok: true, elapsed_ms,
payload: { ... per kind } }`. But the per-kind payload shape is never
typed. If `navigate` returns `{ final_url, http_status }` and `click`
returns `{ }`, drift between Java and TS expectations is invisible to
the compiler on either side. Should this be a per-kind sealed type?

**C. A11y bbox is viewport-local; iframes have separate viewports**

C1 says the a11y content script runs in all_frames. Each frame's
`getBoundingClientRect()` is local to that frame's viewport. The
Orchestrator sees the bbox `(540, 320, 80, 32)` and emits a click at
those coordinates — but in an iframe, that's not the same as (540, 320)
on the **page**. Does the plan handle this? Should the bbox be
absolute-page coordinates? Should the a11y line carry a `frame_id`?

**D. A11y tree freshness — when does the SW re-extract?**

After `navigate`, the DOM changes completely. Old ref_N ids are dead.
After `click`, the DOM may change (modal opens, navigation happens).
Does the plan say when the Orchestrator must re-call `a11y.snapshot.request`?
If a `Step` carries a `ref_N` from before a navigate, will the click
hit the wrong element?

**E. chrome.debugger banner UX per tab**

Each `chrome.debugger.attach(tabId)` shows the yellow banner on that tab.
If the agent works across multiple tabs in the same group, the user sees
a banner appear on each tab as the agent visits it. The plan acknowledges
the banner but doesn't reason about multi-tab attaching. Acceptable in
Phase 2? Or do we need to document a "the banner will follow the cursor"
expectation?

**F. F3 DomEngine's grounding is brittle**

Phase 2 DomEngine matches `(role, name_pattern)` against a11y lines.
If a page has two buttons both labeled "Submit", the engine returns the
first. Is that a bug or acceptable Phase-2 simplification? At minimum
should the engine return a `GroundingResult` that flags ambiguity so
the planner can fall back?

**G. WindMouse output is consumed where?**

B9 produces a list of waypoints. B7 `move_mouse` is a single
target. F1 emits one MOVE_MOUSE per step. So WindMouse is unused
in Phase 2? Or is the planner supposed to call WindMouse and emit N
MOVE_MOUSE actions for one logical step?

This is a real ambiguity. Either:
- F1 should explicitly call WindMouse and emit a list of MOVE_MOUSE
  actions interleaved with small `wait(time)` actions, OR
- B7 should accept a `path` parameter with multiple waypoints and
  internally walk them with timing.

Pick one and make it explicit in the plan.

**H. `indicator.show` precedence**

If `indicator.show` is sent twice in quick succession (Control Plane bug
or retry), does the content script idempotently re-mount, or does it
end up with two cursors? Verify the visual components are idempotent on
re-show.

---

## Expected output

Match the format of `2026-05-28-browser-agent-foundation.audit-response.md`:

For each finding produce:

```
### P{0|1|2}-N — <one-line summary>

**Status:** ACCEPTED / NEEDS_CLARIFICATION / REJECTED (with reason)

**Root cause:** <what's wrong>

**Decision / Patch:** <what to change in the plan>

**Affected tasks:** <list of P/B/C/D/F/E task ids>

**Re-audit criterion:** <how a second audit can verify the fix>
```

Severity rubric:
- **P0**: foundational mistake — the loop will not work end-to-end OR will
  produce wrong behaviour silently. Must fix before any implementation.
- **P1**: design or test gap — the plan would let an implementer ship code
  that passes the listed tests but fails a real-world case.
- **P2**: improvement / future-proofing — won't block Phase 2 but should be
  noted so we don't pay interest in Phase 3.

Group findings by hot-spot # or A–H bug letter when applicable.

End with a single "Overall recommendation":
- **READY**: zero P0, ≤2 P1, any P2.
- **NEEDS PATCH**: any P0 or >2 P1.
- **REWRITE**: ≥3 P0 or fundamental scope disagreement.

---

## Constraints on the audit (don't waste my round-trip)

1. **Don't audit Phase 1.** It's already merged-equivalent and audited.
   Only look at Phase 1 docs to confirm the contract Phase 2 inherits.
2. **Don't propose new features.** Audit is for correctness, not for
   "wouldn't it be nice if we also had X". Phase 3 has its own plan slot.
3. **Don't worry about IP / licensing.** All code in the plan is meant to
   be net-new MateClaw code.
4. **Don't worry about brand colors or specific SVG paths.** Those are
   placeholder choices to be filled in by the designer; not your problem.
5. **DO worry about contract gaps between Java/Go/TS sides.** The three
   runtimes must agree on every envelope shape.
6. **DO worry about TDD discipline.** Every task should show
   `write test → see fail → implement → see pass → commit`. If a task
   skips the explicit "see fail" step, flag it as P1 (regardless of
   apparent code quality).
