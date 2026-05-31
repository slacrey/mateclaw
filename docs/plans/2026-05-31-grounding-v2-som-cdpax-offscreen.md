# Browser Grounding v2 — Set-of-Mark + CDP-native AX tree + Offscreen WS

Status: in progress (2026-05-31)
Owner: driven by the browser-agent thread; built in parallel worktrees, integrated + audited inline.

## Why

The extension-driven agent kept failing on real sites (Douyin 搜索→筛选→按点赞排序) because three
layers each have a structural gap that we'd been patching per-site:

1. **Perception** — the custom JS walker (`window.__mateclaw_a11y_tree`) misses custom widgets
   (role-less `<span>` options, hover panels), so role+name grounding can't see them.
2. **Universal fallback** — vision grounding existed but was DEAD (two bugs: 64 KB WS text buffer,
   then `captureVisibleTab(-1)` invalid windowId). Now fixed; it can finally receive a screenshot.
3. **Connection** — the MV3 service worker idle-suspends (~30 s), dropping the WS (CloseStatus
   1001/1006) mid-task. A keepalive ping helps but is a hack.

Move all three to the mature, industry-standard approach. Scope is Chromium-only (already true:
`chrome.debugger` input + MV3) — CDP works on all Chromium browsers; no cross-browser regression.

## Workstreams (independent file boundaries → parallel-safe)

### A. Set-of-Mark (SoM) vision grounding — SERVER-side
Goal: the universal "see it → click it" fallback. When DOM + A11y miss, annotate the screenshot
with numbered boxes over candidate elements, ask the VLM to pick a NUMBER (not raw coords — avoids
coordinate hallucination), map number → element bbox → click.

- Candidate boxes come from the current snapshot's parsed lines (`PageSnapshot.lines()` — each has
  bbox + role + name). Draw numbered rectangles on the screenshot via Java2D (BufferedImage).
- New `SetOfMarkAnnotator` (server): input PNG/JPEG bytes + List<bbox,label> → annotated JPEG + the
  index→bbox map.
- `VisionEngine`: add a SoM path — when grounding by A11yMatch and the structural engines missed,
  annotate with the snapshot's candidate boxes, prompt "return the NUMBER of the element matching
  <intent>, or -1", parse the number, return Hit(bbox of that number). Keep the existing free-form
  coord vision as a secondary fallback when SoM returns -1.
- Files: `mateclaw-server/.../orchestrator/engine/VisionEngine.java`, new
  `.../engine/SetOfMarkAnnotator.java`, tests. Do NOT touch the extension.
- Accept: unit test — given a snapshot with 3 boxes + a stub VLM returning "2", grounds to box 2's
  bbox. VisionEngine still falls back to coord-mode on -1.

### B. CDP-native accessibility tree — EXTENSION-side
Goal: replace the injected JS walker with Chrome's own computed tree for far better coverage.

- In `snapshot-request-handler.ts`, capture via `chrome.debugger` CDP instead of
  `chrome.scripting.executeScript`:
  - `Accessibility.getFullAXTree` (roles, names, backendDOMNodeId, parent/child),
  - `DOMSnapshot.captureSnapshot(['computed-style'])` OR `DOM.getBoxModel` for bounds.
- Emit the SAME text-line format the server parses (frozen contract —
  `Role[ref=ref_N, frame=N]: name @{x,y wxh}`, see server `PageSnapshot.LINE_PATTERN`). ref_N may
  map to backendDOMNodeId so a future click-by-ref is exact.
- Keep `a11y-tree.ts` as a FALLBACK when the debugger isn't attached / CDP errors (don't delete).
- Filter parity: interactive | default | all must still mean the same buckets.
- Files: `mateclaw-extension/src/sw/snapshot-request-handler.ts`, new
  `src/sw/cdp-ax-extractor.ts`, tests. Do NOT touch the connection/offscreen code.
- Accept: a fixture AX tree maps to correctly-formatted lines incl. bbox; existing
  snapshot-request-handler tests still pass; offline probe shows Douyin 筛选 + options captured.

### C. Offscreen-document WebSocket — EXTENSION-side (highest risk; integrate last)
Goal: hold the edge WS in an MV3 offscreen document so it survives SW idle-suspension → no more
1001/1006 disconnects.

- `chrome.offscreen.createDocument` (reason: a persistent-connection justification); offscreen.ts
  owns the `DirectBridgeClient`. SW ↔ offscreen relay inbound/outbound EdgeMessages via
  `chrome.runtime` messaging. SW keeps owning action/snapshot/screenshot dispatch.
- Manifest: add `offscreen` permission + offscreen.html entry (vite).
- Preserve HELLO/heartbeat/reconnect + the existing direct-bridge tests.
- Files: `src/offscreen/*`, `public/manifest.json` + root `manifest.json`, `src/sw/index.ts`
  (connection bootstrap only), `vite.config.ts`. 
- Accept: SW suspended (simulate) → WS stays open in offscreen; action still round-trips; no
  regression in direct-bridge tests.
- NOTE: the 0.1.2 keepalive already cut disconnects 4→1 in the last run. Confirm whether C is still
  needed before merging; if keepalive suffices, C becomes optional hardening.

## Integration / audit (me)
Build A + B in parallel worktrees; integrate + run full test suites (TS vitest + Java surefire) +
offline Douyin probes per workstream; bump extension version; restart backend. Then C, with a
keepalive-necessity check first. Nothing merges to the working tree until its tests are green and I've
audited the diff.
