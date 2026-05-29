# MateClaw Browser Agent — Phase 3 Plan

Date: 2026-05-29 (revised 2026-05-29 after user steer)
Author: Claude Opus 4.7 (1M context)
Status: **v1.1 — user decisions locked in; ready to dispatch Wave 3-A1**
Branch (proposed): `feat/browser-phase-3`

## v1.1 changes (user decisions)

| Open question | Decision |
|---|---|
| Thrust priority | **Thrust A first.** "先完成能打开浏览器达成目标 然后再考虑sop". Thrust B (SOP) deferred to after Thrust A is proven end-to-end. |
| Vision engine model | **MateClaw failover chain** (per-tenant config). |
| Trajectory storage | **JSONL on disk** (Phase 3 ships fast; Phase 4 migrates to Postgres). |

## v1.1 plan-gap fix — Agent loop

The original draft assumed the caller hand-crafts each `Step` for the planner. To genuinely "open browser and complete a goal", we need the LLM to drive the dispatch loop autonomously. The existing MateClaw `StateGraphReActAgent` already does this for server-side Playwright (via `BrowserUseTool.java` — see `vip/mate/tool/builtin/`). Phase 3 adds a **sibling tool** that targets the user's real Chrome via the Phase 2 stack:

**T3.AGENT-TOOL — `ExtensionBrowserTool`** (new `@Tool` callbacks under `vip/mate/tool/builtin/`):
- `browser_navigate(url, wait_for)` → builds `ActionRequest.Navigate`, calls `actionExecutionService.execute`.
- `browser_click(hint_text, near_label?)` → constructs `GroundingHint.A11yMatch`, calls `GroundingDispatcher.ground`, hands the resulting `Step` to `ActionPlanner`, runs through `PlanExecutionService`.
- `browser_type(text)` / `browser_scroll(direction, distance)` / `browser_wait(strategy)` — similar shape.
- `browser_observe()` → returns the current `PageSnapshot.tree` as a text blob for the LLM's next reasoning turn.
- `browser_screenshot()` → returns a base64 PNG (uses T3.2-protocol's `screenshot.capture.*` wire kinds).
- All callbacks honour Phase 1's session-id ownership: the tool resolves the active session from `ToolContext` → `BrowserSession` → routes through the existing transport.

Tests: 5-7 cases verifying each `@Tool` annotation produces the right `ActionRequest`. Integration test: feed a goal to the ReAct agent + a stub LLM that emits the expected tool sequence → verify the Chrome side received the right CDP events (via Phase 2's `RecordingWsSink` fixture).

This tool COEXISTS with the existing server-side `BrowserUseTool`. Tenants pick which surface (own headless vs user's real Chrome) per agent config.

## Revised Wave structure (v1.1)

```
Wave 3-A1 (parallel — foundation):
  - T3.A iframe-internal grounding  (Codex 19)
  - T3.2-protocol screenshot.capture.* wire kinds  (Codex 20)
  ↓
Wave 3-A2 (parallel — engines + tool):
  - T3.1 A11yEngine real impl  (Codex 21)
  - T3.2 VisionEngine real impl using failover chain  (Sub-agent H)
  - T3.AGENT-TOOL ExtensionBrowserTool  (me inline)
  ↓
Wave 3-A3 (smoke + audit):
  - End-to-end manual: "search 'mateclaw' on duckduckgo.com → return first result title"
    Agent uses ExtensionBrowserTool in user's real Chrome.
  - Phase 1 audit script + new T3.A bbox-translation invariant
  - Wave A closure report

  Then user verifies "open browser, complete goal" is real, and we decide
  whether to start Thrust B (SOP).
```



---

## One-line summary

**Phase 3 turns "given a plan, the agent executes" into "given a natural-language goal, the agent figures out the plan."** It adds semantic grounding (A11y + Vision engines), SOP learning (record a successful trajectory once → replay it fast and token-cheap), drift detection (notice when SOPs break), and the first concrete adapter (douyin) as a proof point.

## What Phase 2 (now done) gave us

```
[Caller with hand-crafted Step + GroundingResult]
     ↓
ActionPlanner → PlanExecutionService.concatMap → ActionExecutionService
     ↓ over WS
Native Host → Extension SW → 6 atomic action handlers → CDP → Chrome
                                  ↑
                          Visual indicators (cursor + glow + stop)
                                  ↑
                          A11y tree extraction + snapshot freshness
                                  ↑
                          DOM grounding engine (DomEngine real;
                                                 A11y + Vision stubs)
```

Phase 2 unit + integration: **464/464 tests**. Phase 1 audit: **GREEN 7/7**. The bottleneck is the **stubbed A11y and Vision engines** — every grounding request that can't be resolved by exact DOM role+name regex falls through to Miss.

## What Phase 3 ships

Two **independent** thrusts plus two supporting workstreams. Either thrust can ship first; the user decides.

```
┌─────────────────────────────────────────┐  ┌──────────────────────────────────────┐
│ Thrust A — GROUNDING INTELLIGENCE      │  │ Thrust B — SOP LEARNING              │
│                                         │  │                                       │
│ T3.1  A11yEngine real implementation    │  │ T3.5  SOP YAML format + parser       │
│ T3.2  VisionEngine real implementation  │  │ T3.6  SOP Synthesizer (from          │
│ T3.A  Iframe-internal grounding (P1-5   │  │       successful trajectory)         │
│       deferred from Phase 2)            │  │ T3.7  Trajectory recording           │
│                                         │  │       (every step's evidence)        │
│ Makes the agent SMARTER at finding      │  │ Makes the agent FASTER + cheaper     │
│ elements on a page it has never seen.   │  │ at repeating a known workflow.       │
└─────────────────────────────────────────┘  └──────────────────────────────────────┘

┌─────────────────────────────────────────┐  ┌──────────────────────────────────────┐
│ Workstream C — DRIFT (cross-cutting)   │  │ Workstream D — FIRST ADAPTER         │
│                                         │  │                                       │
│ T3.3  Drift detection (per-step DOM /   │  │ T3.8  Douyin Adapter — a YAML SOP    │
│       A11y / Vision hit-rate tracking)  │  │       for "find comments on a video, │
│ T3.4  Drift repair (LLM-assisted        │  │       extract them" — proves the     │
│       candidate generation, gray rollout) │  │     full Thrust A + Thrust B loop.   │
└─────────────────────────────────────────┘  └──────────────────────────────────────┘
```

## What Phase 3 deliberately defers to Phase 4+

- Multi-user / mTLS / hardened auth
- Database-backed sessions (`hello.resume`)
- SQLite outbox in Native Host
- StepRun checkpoint commit boundary
- Recovery Matrix + state restoration after crash
- Multi-tab orchestration UI (sidepanel binding controls)
- Headless / scaled-out execution

These are Phase 4 concerns. Phase 3 stays on the **single-tab single-user with one Chrome window** model.

---

## Thrust A — Grounding intelligence

### Goal

Replace the two Phase-2 stub engines with real implementations that close two failure modes:

| Failure Phase 2 has today | Closed by |
|---|---|
| "Submit" appears in 2 buttons → `Ambiguous` → planner throws → user has to pick | T3.1 A11yEngine narrows by ancestor role / proximity to a hint label |
| "Find the search box" with no exact text match → DomEngine `Miss` → no fallback | T3.2 VisionEngine calls a multimodal LLM with screenshot + intent → returns `Hit` with bbox |
| Element inside an iframe → DomEngine never sees it (manifest is `all_frames: false`) | T3.A iframe grounding (frame-offset bbox translation + frame_id tagged refs) |

### Tasks

#### T3.1 — A11yEngine real implementation

Lives at `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/A11yEngine.java`. Already declared as `@Component implements GroundingEngine`; currently returns `Miss("stub-phase-2")`.

**Behavior** when receiving `Ambiguous` from upstream DomEngine (the dispatcher cascades):
1. Walk the candidate list. For each candidate, find its parent role chain in the snapshot tree.
2. If the `GroundingHint` carries a hint label (Phase 3 extends `GroundingHint` with an optional `nearLabel` field), filter to candidates whose nearest ancestor with role `heading|landmark|section|article` contains that label.
3. If exactly one candidate survives → return `Hit(it, "narrowed by parent " + parentRole)`.
4. If multiple survive → return `Ambiguous(survivors, ...)` to next engine.

Tests: 6–8 cases. Should be do-able by Codex.

#### T3.2 — VisionEngine real implementation

Lives at `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/VisionEngine.java`.

**Behavior**:
1. Capture screenshot via Phase 2 transport (need a new `screenshot.capture.request` wire kind — see "Spec gaps" below).
2. Call MateClaw's existing LLM failover chain (`vip.mate.llm.failover.*`) with a multimodal prompt:
   - System: "You are grounding an action on a web page. The user wants to click {hint.describe()}."
   - User: { screenshot image, viewport size, hint text }
   - Constrained output: `{ found: bool, x: int, y: int, w: int, h: int, confidence: float }`
3. If `found && confidence > 0.6` → `Hit(GroundedTarget(bbox), "vision: " + reason)`.
4. Else → `Miss("vision: " + reason)`.

**Spec gap**: Phase 2 has no `screenshot.capture.*` wire kind. T3.2 must extend `EdgeMessageKind` v1.2:
- `screenshot.capture.request` (CP → Ext) + `screenshot.capture.response`
- Extension side: `chrome.tabs.captureVisibleTab` (no permission needed beyond existing `activeTab` / `<all_urls>`)

Tests: 5–7 cases. Mock the LLM client; verify the prompt shape + result parsing.

#### T3.A — Iframe-internal grounding (Codex P1-5 deferred from Phase 2)

Three coordinated changes:

1. **`manifest.json`** — A11y content script back to `all_frames: true`.
2. **`a11y-tree.ts`** — accumulate `window.frameElement.getBoundingClientRect()` chain when walking inside an iframe; tag each line with `frame_id: <window.parent === window ? 0 : chrome.runtime.id-extracted>` so the resolver can find the right frame later.
3. **SW `SnapshotRequestHandler`** — when CP asks for grounding inside a non-top frame, use `chrome.scripting.executeScript({target: {tabId, frameIds: [frameId]}, ...})` instead of `allFrames: false`.

Tests: 4–6 cases. The bbox-translation case is the load-bearing one (assert a button inside a 200px-offset iframe shows `bbox.y` = local-y + 200).

---

## Thrust B — SOP learning

### Goal

Two superpowers, one shared substrate (the trajectory log):

1. **Skip the LLM entirely** for known workflows. A douyin scrape that takes 4 LLM calls becomes 0 LLM calls after the first successful run.
2. **Surface failure** — when a SOP breaks, you know exactly which step + which engine couldn't ground.

### Tasks

#### T3.7 — Trajectory recording (foundation; T3.5 + T3.6 depend on this)

Every successful (and failed) plan execution writes a structured record:

```
TrajectoryRecord {
  trajectoryId: UUID,
  goalText: string,                    // "find comments on the active video"
  startedAt, completedAt,
  outcome: SUCCESS | PARTIAL | FAILURE,
  steps: List<TrajectoryStep>,
}
TrajectoryStep {
  stepIndex: int,
  intent: { kind: 'click'|'type'|...,  // came from caller
            hint: GroundingHint,
            nearLabel?: string },
  grounding: {                          // what the dispatcher decided
    engine: 'dom'|'a11y'|'vision',
    evidence: string,
    target: BBox,
    refId?: string,
  },
  actionResult: ActionResult.Success | Failure,
  snapshotBefore: snapshot_id,         // points to PageSnapshot stored separately
  snapshotAfter: snapshot_id,
  llmCalls: List<LlmCallSummary>,      // for cost auditing
  elapsedMs: int,
}
```

Storage (Phase 3 scope): JSONL append at `mateclaw-server/data/trajectories/<date>/<trajectoryId>.jsonl`. Phase 4 adds Postgres+S3.

Tests: 4–6 cases. Wire `PlanExecutionService` to call a `TrajectoryRecorder` per step.

#### T3.5 — SOP YAML format + parser + executor

```yaml
# A SOP is a *deterministic* replay of a previously-recorded successful trajectory,
# with each step's "how to ground" already burned in.
sop:
  id: douyin.find-comments-and-extract
  description: |
    On a douyin video page, click "Comments", scroll the panel to load all,
    extract them as a JSON list.
  version: 1
  steps:
    - intent: click
      ground:
        # Tier 1: fast & deterministic. Try first.
        strategy: a11y_ref_replay
        ref_template: 'button:Comments'      # role:name; ref_N is regenerated each call
      then: { wait_for: network_idle, deadline_ms: 5000 }

    - intent: scroll
      ground:
        strategy: bbox_replay
        bbox: { x: 1080, y: 200, w: 360, h: 600 }
      params: { direction: down, distance_px: 1000, segments: 8 }
      repeat_until:
        condition: a11y_count_stable
        ref: 'article:*'
        stable_for_ms: 1500

    - intent: extract
      ground:
        strategy: a11y_selector
        role_re: 'article|comment'
      capture: { as: comments, fields: [text, author, time] }
```

The executor is `SopExecutor` (new orchestrator service): translates each step into the same `ActionRequest` pipeline F4 already runs, but with the `ground` strategy as a hint to `GroundingDispatcher` to short-circuit. Failure → demote to "this SOP needs repair" → fall back to LLM-driven planning.

#### T3.6 — SOP Synthesizer (from trajectory)

Given a successful `TrajectoryRecord`, generate a SOP YAML:
- For each step, look at `grounding.engine` + `grounding.refId` / `grounding.evidence` and pick the most stable replay strategy:
  - DOM hit → `strategy: a11y_ref_replay`
  - Vision hit → `strategy: bbox_replay`
  - A11y hit with unique role+name → `strategy: a11y_selector`
- Surface the YAML in a sidepanel "Save as SOP" UI (Phase 3 ships the file; sidepanel UI is optional polish).

Tests: 3–5 cases.

---

## Workstream C — Drift detection + repair

### T3.3 — Drift detection

When a step's primary grounding strategy returns `Miss` but the fallback engine succeeds, **the trajectory step records both**. A background `DriftAnalyzer` (`@Scheduled`) walks recent trajectories and emits a `DriftEvent` when:
- A SOP's `a11y_ref_replay` strategy missed 3 times in 7 days for the same step.
- A vision-only grounding succeeded for the same intent that previously succeeded via DOM (DOM has rotted).

Surface as admin-UI dashboard rows (sidepanel can show "5 SOPs need attention").

### T3.4 — Drift repair (LLM-assisted candidate generation, gray rollout)

For each drifting step:
1. LLM looks at the old failing strategy + the new successful trajectory's evidence + the page snapshot.
2. Proposes new YAML for that step.
3. New SOP version is created with `gray_rollout: 0.1` — 10% of next runs use it.
4. If success rate > 95% over 10 runs, gray rollout promotes to 100%.

This is the most ambitious Phase 3 task. May ship in a Phase 3.1 follow-up.

---

## Workstream D — First Adapter (proof point)

### T3.8 — Douyin Adapter

A `mateclaw-server/src/main/resources/sops/douyin-extract-comments.yaml` file. Manually written or synthesized from a recorded successful trajectory. Used in the Phase 3 acceptance smoke (next section).

This is the **single most important deliverable for proving Phase 3 works end-to-end**.

---

## Wave structure (proposed)

Phase 3 is independent enough to ship one thrust at a time. Proposed order **Thrust A first** (because Thrust B's recorded trajectories want non-stub engines to be meaningful):

| Wave | Tasks | Parallelizable? |
|---|---|---|
| **3-A1** | T3.1 A11yEngine | Yes — 1 Codex / sub-agent |
| **3-A2** | T3.2 VisionEngine + screenshot.capture.* wire kind | Bundle: 1 sub-agent (touches both LLM layer + protocol layer) |
| **3-A3** | T3.A Iframe grounding | Yes — 1 Codex (manifest + a11y-tree + SnapshotRequestHandler) |
| **3-A audit** | Cross-runtime + Phase 1 audit + new Thrust-A invariants | I do |
| **3-B1** | T3.7 Trajectory recording (foundation) | I do directly — touches PlanExecutionService |
| **3-B2** | T3.5 SOP YAML parser + executor | Yes — 1 sub-agent |
| **3-B3** | T3.6 Synthesizer | Yes — 1 Codex |
| **3-C** | T3.3 Drift detection (T3.4 deferred to 3.1) | 1 Codex |
| **3-D** | T3.8 Douyin Adapter + end-to-end | I do + manual smoke |
| **3-final** | Phase 3 closure report | I do |

Approximate scope: **~6 Codex tasks + 4 Claude sub-agents + 4 inline tasks**. Bigger than Phase 2 Wave 4 but smaller than Wave 2.

---

## Spec gaps to close before execution

1. **`screenshot.capture.*` wire kinds** — add 2 new `EdgeMessageKind` values to v1.2 of the protocol (Java + NH bridge + extension + spec doc).
2. **`GroundingHint` extension** — add `nearLabel` field on `A11yMatch`; optional `intent_description` for Vision.
3. **`GroundingHint.VisionGround`** new sealed variant for vision-specific intents (no role/name pattern; just a natural-language description).
4. **`TrajectoryRecord` schema** — new package `vip.mate.browser.trajectory` with sealed types + JSONL writer.
5. **`SopSchema`** — new types in `vip.mate.browser.sop` for YAML deserialization.

---

## P0 / P1 invariants for Phase 3

**P0 (will pin acceptance gates)**:
- **P0-3-A1**: A11yEngine narrows by parent role correctly even when the same role appears at multiple ancestor levels (precedence: nearest match wins).
- **P0-3-A2**: VisionEngine confidence threshold is configurable AND honored — a confidence-0.4 LLM hit MUST NOT promote to action without explicit caller override.
- **P0-3-B1**: TrajectoryRecorder writes are atomic — partial crashes leave either a complete record or no record (never half), proven via interrupt-mid-write test.
- **P0-3-B2**: SOP executor honours the SAME `concatMap` discipline as PlanExecutionService — replay never parallelises steps. Same audit grep applies.

**P1**:
- **P1-3-A3 iframe-bbox-translation**: A button at local (10,10) inside an iframe at page (200,300) reports bbox (210, 310). Single load-bearing test.
- **P1-3-B**: SOPs are forward-compatible — a v1 SOP runs unmodified on v2 executor; v2 fields are optional.
- **P1-3-C**: Drift events are debounced (one event per (sopId, stepIndex, day)); no flood from a single bad day.

---

## Acceptance gates

**Automated**:
1. `mvn test` 0 failures (target: 220+ in `vip.mate.browser.**`, was 190 at Phase 2.1 close).
2. `pnpm test` extension still ≥247 (Thrust A may add ~10–15 for iframe + screenshot capture round-trips).
3. `bash scripts/audit-phase-1.sh` still GREEN.
4. **New grep**: `grep -rn -E 'flatMap|Flux\.merge|Mono\.zip|allOf' src/main/java/vip/mate/browser/sop/` returns 0. Same P0-2 discipline as F4.

**Manual smoke**:
5. T3.8 douyin adapter: record one successful trajectory → save as SOP → re-run; assert 0 LLM calls on the replay.
6. T3.A iframe smoke: a contained iframe + button at known offset; agent grounds and clicks it.
7. T3.2 vision smoke: hide the test page's accessible names so DOM/A11y miss; verify Vision engine recovers with a screenshot.

---

## Phase 4 preview (NOT in Phase 3)

- DB-backed trajectories (Postgres) + S3 snapshots
- Multi-user / mTLS / hardened auth (`hello.resume`, post-upgrade revocation)
- SQLite outbox in Native Host
- StepRun checkpoint commit boundary
- Recovery Matrix + state restoration after crash
- Multi-tab orchestration UI (sidepanel binding controls)
- Headless / scaled-out runner

---

## Open decisions for the user

Three things I want a steer on before dispatching Wave 3-A1:

1. **Thrust priority**: do Thrust A (intelligence) first, or interleave with Thrust B (SOP) so the douyin smoke can drive both?
2. **Vision engine model choice**: use the **existing MateClaw failover chain** (configured per-tenant) or **a fixed default** (claude-4.5-sonnet vision) for Phase 3? Failover is more flexible; fixed is more reproducible for SOP replay.
3. **Trajectory storage format**: stick with **JSONL on disk** for Phase 3, or invest now in Postgres + JSON columns? JSONL is fast to ship; Postgres unlocks SQL-side drift analysis from day 1.

Once you give me direction on these, I'll start Wave 3-A1.

---

## One-line summary

**Phase 3 closes the loop from "agent that can execute an explicit plan" to "agent that figures out the plan from intent and remembers what worked," with douyin as the proof point.**
