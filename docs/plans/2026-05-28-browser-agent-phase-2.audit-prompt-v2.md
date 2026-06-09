# Phase 2 Audit Prompt v2 — Patch Verification Pass

> Round 2. Round 1 produced 14 findings (3 P0, 9 P1, 3 P2); all 14 have been
> patched and the Phase 2 plan is now v1.1. **This pass is verification, not
> re-audit.** Do not re-evaluate the 9 hot-spots from round 1 — assume they
> are still in force. Instead, confirm that each patch *actually landed in
> the right place* and that the dispositions in `audit-response.md` match
> what is in the v1.1 plan.

---

## Required reading (in this order, do not skim)

1. `docs/plans/2026-05-28-browser-agent-phase-2.audit-response.md` — the
   contract. Every finding has a "Patches" subsection naming the exact file
   sections and tests that should now exist.
2. `docs/plans/2026-05-28-browser-agent-phase-2.md` (v1.1) — the document
   under verification. The "Revision history" header tag should read v1.1.
3. `docs/plans/2026-05-28-browser-agent-phase-2.audit-prompt.md` — round 1
   prompt, for context only. Do **not** redo round 1's checks unless you
   suspect a regression.

You can skip Phase 1 docs entirely for this pass.

---

## Stop conditions

- If the plan's revision history does NOT say v1.1 → **stop**, report "patch
  not landed" and exit.
- If `audit-response.md` is missing → **stop**, report missing file.
- If a verification check below finds the patch absent → record as P0
  regression and keep going (other checks may also fail; we want the
  complete picture).

---

## The 4 critical verification points

These mirror the "second Codex pass" list at the end of `audit-response.md`.
Each is a *specific* contract that must hold. Do not generalise; do not
audit beyond the named contract.

### V1 — P0-1 multi-tab routing (genuine, not test-trivial)

**Check 1.1 — Test exists with the named scenario.**
- Open the `mateclaw-server` integration test:
  `src/test/java/vip/mate/browser/integration/EndToEndOrchestrationTest.java`
- Find a test method named `multiTab_actionExecuteLandsOnlyOnResolvedTab`.
- Verify the test fixture creates **two** mock tabs (or two distinct tab
  ids in the sink expectations). If only one tab id appears, the test is
  *trivially* satisfied and fails this check.

**Check 1.2 — `tab_ref` propagation visible end-to-end.**
- Grep the v1.1 plan: `grep -n 'tab_ref' docs/plans/2026-05-28-browser-agent-phase-2.md`
- Confirm `tab_ref` appears in: the spec section under P1, the `ActionRequest`
  Java record signature under P2, the F1 `ActionPlanner` test method, the
  B10 wiring test, the B11 snapshot handler, and the E1 multi-tab test.
- Missing in any of these → P0 regression.

**Check 1.3 — `NO_TARGET_TAB` failure code is defined and tested.**
- In B10 and B11 tests there must be a case asserting the SW emits a result
  with `code: 'NO_TARGET_TAB'` when `tab_ref` cannot be resolved.

### V2 — P0-2 sequential plan execution (no hidden parallelism)

**Check 2.1 — Sequence test wall-clock assertion is load-bearing.**
- In `EndToEndOrchestrationTest`, find `sequencing_clickSentStrictlyAfterMoveResult_andAfter180ms`.
- The test must:
  - Record both move-start and click-sent timestamps with `System.currentTimeMillis()`.
  - Assert `clickSentAt - moveStartedAt >= 180`.
  - Assert `sink.maxConcurrentInFlight() == 1`.
- Missing the wall-clock delta OR the concurrency invariant → P0 regression.

**Check 2.2 — Grep proves no parallel constructs on the action stream.**
- Search for parallel reactive primitives:
  ```
  grep -rE 'flatMap|Flux\.merge|Mono\.zip|allOf|combineLatest' \
       mateclaw-server/src/main/java/vip/mate/browser/orchestrator/
  ```
- The grep should return either zero matches OR matches only in code that
  does not touch action dispatch (e.g. unrelated metrics aggregation).
- Any hit on `ActionExecutionService` or `PlanExecutionService` call sites
  → P0 regression.

**Check 2.3 — F4 task exists and uses `concatMap`.**
- The plan must have a Task **F4** section titled `PlanExecutionService`.
- Its implementation skeleton must literally contain the string
  `Flux.fromIterable(plan).concatMap(...)` (not `.flatMap`).

### V3 — P0-3 snapshot freshness round-trip

**Check 3.1 — F5 task exists with the right state machine.**
- The plan must have a Task **F5** section titled `PageSnapshotService`.
- Its state machine must list all four transitions:
  - NAVIGATE success → STALE
  - CLICK/TYPE/SCROLL success → SUSPECT
  - age > 30s → STALE
  - `event.tab.closed` → cache entry removed
- Missing any transition → P0 regression.

**Check 3.2 — End-to-end test proves the round-trip.**
- In `EndToEndOrchestrationTest`, find `snapshotRefresh_navigateInvalidatesRefIds`.
- Assertions must include `verify(snapshotClient, times(2))` (initial +
  post-navigate refresh). A `times(1)` or unchecked invocation is a
  P0 regression.

**Check 3.3 — B11 task exists and snapshot envelope carries id+timestamp.**
- The plan must have a Task **B11** named `SnapshotRequestHandler`.
- The response envelope payload must include `snapshot_id` (UUID),
  `captured_at_ms`, and `tab_ref` (echoed back as the resolved absolute id).

### V4 — P1-2 cancel CAS race (the broken-impl regression test)

**Check 4.1 — Compare-and-remove is mandatory everywhere.**
- Grep the plan (or the targeted Java file once implementation lands):
  ```
  grep -nE 'inflight\.remove\([^,]+\)' mateclaw-server/src/main/java/vip/mate/browser/edge/action/
  ```
- A single-argument `remove(sessionId)` is a P1 regression — only the
  two-argument compare-and-remove `remove(sessionId, holder)` is allowed
  in `ActionExecutionService`.

**Check 4.2 — Late-cancel test asserts the new holder survives.**
- Find `lateCancel_doesNotRemoveSubsequentActionHolder` in
  `ActionExecutionServiceTest`. It must:
  - Complete a first action successfully.
  - Register a second action.
  - Send a cancel for the first action's correlation id.
  - Assert the cancel returns `false` AND the second action's future is
    still `notDone()`.

**Check 4.3 — Timeout/result race covered.**
- Find `timeoutLosesRaceToResult_doesNotDoubleComplete`. The test must
  use a short deadline (e.g. 50 ms), deliver a result within the deadline,
  sleep past the deadline, and assert the future resolved to `Success`,
  not `DEADLINE_EXCEEDED`.

---

## Drift checks (sanity, ≤ 10 minutes total)

These are cheap regression scans, not new audits.

**D1 — Untrusted-event ban (still in force)**
```
grep -rE '\.click\(\)|dispatchEvent\(new (Mouse|Keyboard)Event' \
     mateclaw-extension/src/sw/action/
```
Must return zero matches. (Hot-spot 4 from round 1.)

**D2 — session_id contract (still in force)**
- Search the plan for `session_id`: every Extension-originated envelope
  in the v1.1 patches (`STOP_AGENT` → `indicator.stop_clicked`, action.result,
  a11y.snapshot.response) must be constructed with `session_id: ""`. If you
  find a literal session id being assigned in any Extension-side code or
  test, that's a regression.

**D3 — WindMouse home matches F1 contract**
- F1's test `clickStep_emitsMoveMouseThenClick_carryingTabRef` must still
  assert exactly **2** action requests (MOVE_MOUSE + CLICK), not N. If F1
  test was modified to expect more actions, P1-6 was misimplemented.

**D4 — Reduced-motion in BOTH visual components**
- `PhantomCursor.test.ts` and `GlowBorder.test.ts` must each contain at
  least one test that mocks `window.matchMedia({matches: true})`. Only
  one of the two → P1-8 partial.

**D5 — TDD discipline visible**
- Plan should contain the phrase `[run per template]` in most B/C/D/F
  tasks (the prologue introduces it). A grep:
  ```
  grep -c '\[per template\]\|\[run per template\]' \
       docs/plans/2026-05-28-browser-agent-phase-2.md
  ```
  Expect ≥ 15 occurrences (one per task that uses the shorthand). Far
  fewer → P1-7 was patched superficially.

**D6 — Iframe scope cut**
- The new content_scripts entry for `assets/a11y-tree.js` in C6 must read
  `"all_frames": false` (not `true`). Plan's "Out of scope (Phase 3+)" list
  must include an entry for iframe grounding.

---

## Expected output

Tight format — round 2 is verification, not re-audit. For each of V1–V4
and D1–D6, produce:

```
### V1 (or D1) — <name>
**Status:** PASS / FAIL / N/A

**Evidence:** <one short paragraph quoting the line/test that confirms,
or naming the specific absence>
```

End with one of:

- **VERIFIED** — all V1–V4 PASS, drift checks all PASS or have a single
  acceptable explanation.
- **PARTIAL** — V1–V4 all PASS but ≥ 1 drift check FAIL.
- **REGRESSED** — any V check FAIL.

If REGRESSED, list the specific patches that need to land and reference
the audit-response.md finding number.

---

## What NOT to do this round

1. **Do not re-run round 1.** The 14 findings are settled.
2. **Do not propose new architectural changes.** Phase 3 has its own slot;
   Phase 2.1 has its own scope.
3. **Do not lecture on style / naming / SVG paths.** Out of scope.
4. **Do not score TDD discipline narratively.** Use D5 grep result.
5. **Do not relitigate the iframe scope cut.** P1-5 disposition is final
   for Phase 2; verify the cut landed, do not re-argue it.

Budget: this round should take a fraction of round 1. If you find yourself
deep-reading individual handler logic, you're out of scope.
