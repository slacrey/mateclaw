# Codex Task 14 — Phase 2 B11：SnapshotRequestHandler (SW)

> 你（Codex）要实现 SW 端的"A11y 快照桥"。Control Plane 通过 NM bridge
> 下发 `a11y.snapshot.request` envelope，SW 必须：
> 1. 解析 `tab_ref` 为具体 Chrome tab id
> 2. 用 `chrome.scripting.executeScript({target:{tabId}, func: () => window.__mateclaw_a11y_tree(filter, depth, maxChars, refId)})` 调 C1 内容脚本注入的全局函数
> 3. 包装结果为 `a11y.snapshot.response` envelope 上行（带 snapshot_id + captured_at_ms）

## 项目背景

阅读这些文件先理清上下文：
- `mateclaw-extension/src/sw/action/action-router.ts` —— B10 已实现 action.execute / cancel / indicator.stop_clicked 三种 envelope 路由的 ActionRouter
- `mateclaw-extension/src/sw/action/tab-ref-resolver.ts` —— B10 的 TabRefResolver，可直接复用
- `mateclaw-extension/src/content/a11y-tree.ts` —— C1 内容脚本，已注入 `window.__mateclaw_a11y_tree(filter, depth, maxChars, refId): string`
- `mateclaw-extension/src/shared/edge-protocol.ts` —— `EdgeMessageKind.A11ySnapshotRequest` / `.A11ySnapshotResponse` 已在 v1.1 定义
- `docs/specs/edge-protocol.md` §"a11y.snapshot.request" / §"a11y.snapshot.response" —— wire format spec

## 你要交付的 2 个文件 + 1 处 SW wiring

### 1. `mateclaw-extension/src/sw/snapshot-request-handler.ts`

```typescript
import { EdgeMessage, EdgeMessageKind, makeEdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'

export interface SnapshotRequestHandlerDeps {
  resolver: TabRefResolver
  /** Chrome API; injectable for tests. Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
  /** Outbound bridge sender — same shape as ActionRouter's sendUp. */
  sendUp: (msg: EdgeMessage) => void
  /** snapshot_id factory (defaults to crypto.randomUUID). */
  uuid?: () => string
  /** captured_at_ms source (defaults to Date.now). */
  clock?: () => number
}

/**
 * Handles inbound a11y.snapshot.request envelopes.
 *
 * Flow:
 *   1. Read payload: { tab_ref, filter, depth, max_chars, ref_id? }
 *   2. resolver.resolve(tab_ref) → tabId; null → respond with
 *      a11y.snapshot.response carrying { ok: false, code: 'NO_TARGET_TAB', ... }.
 *      Wait — a11y.snapshot.response doesn't have ok/code; the failure
 *      contract for snapshots is the SAME as action.result: a Failure
 *      envelope with code/message/retryable. (See spec §"a11y.snapshot.response".)
 *      Match the wire shape to docs/specs/edge-protocol.md exactly.
 *      The actual spec emits the response envelope with the failure code in
 *      its payload alongside the empty tree+viewport — pick the cleanest
 *      interpretation: respond with { snapshot_id, captured_at_ms, tab_ref:
 *      -1, tree: '', viewport: {w:0,h:0}, error: { code: 'NO_TARGET_TAB',
 *      message } }. Document the choice in a comment.
 *   3. Otherwise call:
 *        chrome.scripting.executeScript({
 *          target: { tabId, allFrames: false },
 *          func: (filter, depth, maxChars, refId) => window.__mateclaw_a11y_tree(filter, depth, maxChars, refId),
 *          args: [filter, depth, maxChars, refId ?? undefined],
 *        })
 *   4. The result is an array of InjectionResult; the first one's `result`
 *      is the string tree (the function's return value).
 *   5. Look up viewport via chrome.tabs.get(tabId) + maybe
 *      chrome.windows.get — actually viewport.{w,h} should come from
 *      window.innerWidth/innerHeight. Inject a second tiny script (or
 *      bundle it into the same call) to return both tree AND viewport in
 *      one round-trip:
 *
 *        func: (filter, depth, maxChars, refId) => ({
 *          tree: window.__mateclaw_a11y_tree(filter, depth, maxChars, refId),
 *          viewport: { w: window.innerWidth, h: window.innerHeight },
 *        }),
 *
 *   6. Build a11y.snapshot.response envelope:
 *        { snapshot_id, captured_at_ms, tab_ref: tabId, tree, viewport }
 *      with session_id="" (NH stamps), in_reply_to=request.msg_id,
 *      trace_id from request.
 *   7. sendUp(response).
 *
 * Catches chrome.scripting.executeScript rejection (e.g. tab navigated
 * away mid-call, content script not present) and emits a response with
 * error.code='SNAPSHOT_FAILED'.
 */
export class SnapshotRequestHandler {
  constructor(private readonly deps: SnapshotRequestHandlerDeps) {}

  async handle(msg: EdgeMessage): Promise<void>
}
```

### 2. `mateclaw-extension/src/sw/snapshot-request-handler.test.ts`

10+ test cases:

```typescript
describe('SnapshotRequestHandler', () => {
  it('happy path: invokes scripting.executeScript with correct args + responds with tree+viewport', async () => {})

  it('uses injected uuid factory for snapshot_id', async () => {})

  it('uses injected clock for captured_at_ms', async () => {})

  it('tab_ref="main" resolves via TabRefResolver before invoking scripting', async () => {})

  it('tab_ref=42 (integer) resolves to 42 verbatim', async () => {})

  it('unresolvable tab_ref → response with error.code=NO_TARGET_TAB, no executeScript call', async () => {})

  it('chrome.scripting.executeScript rejection → response with error.code=SNAPSHOT_FAILED', async () => {})

  it('in_reply_to preserved from request msg_id', async () => {})

  it('trace_id propagated from request', async () => {})

  it('session_id="" on outbound (P0-1 invariant)', async () => {})

  it('passes filter/depth/max_chars/ref_id as args to executeScript', async () => {
    // payload: { filter: 'interactive', depth: 10, max_chars: 50000, ref_id: 'ref_3' }
    // executeScript args should be ['interactive', 10, 50000, 'ref_3']
  })

  it('omits ref_id when not provided in request', async () => {
    // args should be ['interactive', 10, 50000, undefined] or omit fourth arg
  })

  it('tree string is passed through verbatim from injection result', async () => {})

  it('viewport {w,h} from injection result is included in response', async () => {})
})
```

Test fixture pattern:

```typescript
function fakeChrome() {
  return {
    chrome: {
      scripting: {
        executeScript: vi.fn(async (opts: any) => [{
          result: { tree: 'Button[ref=ref_1]: Submit', viewport: { w: 1280, h: 800 } },
          frameId: 0,
        }]),
      },
    } as unknown as typeof globalThis.chrome,
  }
}
```

### 3. Update `mateclaw-extension/src/sw/index.ts`

Add the wiring at the end of the existing SW entry:

```typescript
import { SnapshotRequestHandler } from './snapshot-request-handler'

// ... after `const router = new ActionRouter({...})`:

const snapshotHandler = new SnapshotRequestHandler({
  resolver,
  sendUp,
})

// Inside the bridge.onMessage callback, add a new branch:
if (m.kind === EdgeMessageKind.A11ySnapshotRequest) {
  snapshotHandler.handle(m).catch(e => {
    console.error('[mateclaw][sw] SnapshotRequestHandler.handle threw', e)
  })
}
```

No new tests for the index.ts wiring (same precedent as B10).

## TDD 步骤

1. 写 `snapshot-request-handler.test.ts`，全 fail。
2. `pnpm test --run snapshot-request-handler` → `Cannot find module`.
3. 实现 `snapshot-request-handler.ts`。
4. 跑 → 全绿。
5. 改 `sw/index.ts` 把新 handler 接入 bridge.onMessage。
6. 跑全套 `pnpm test --run` 确认 168/168 仍绿 + 新增的 ~14 个测试也绿（共 ~182）。
7. Commit。

## Commit message template

```
feat(extension): A11y snapshot request handler — bridges CP → C1 content script (B11)

SW receives a11y.snapshot.request from the NM bridge, resolves tab_ref
via the existing TabRefResolver, invokes chrome.scripting.executeScript
to call window.__mateclaw_a11y_tree on the resolved tab, and wraps the
result (tree + viewport) in an a11y.snapshot.response envelope back
upstream.

- Injectable uuid / clock / chrome for fully deterministic tests
- Single executeScript call returns { tree, viewport } in one round-trip
- NO_TARGET_TAB / SNAPSHOT_FAILED typed error responses on resolution
  or injection failure
- trace_id propagated from request; session_id="" preserved (P0-1)

~14 vitest cases covering happy path + 4 args + 2 failure modes +
3 envelope invariants.

sw/index.ts gains the bridge.onMessage A11ySnapshotRequest branch.

Phase 2 Wave 3 — task B11.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要碰 `action-router.ts` —— B11 是平行的独立 handler，不进 ActionRouter（语义不同：动作请求 vs 快照请求）。
- 不要修改 `a11y-tree.ts` —— C1 已 final。
- 不要碰 `tab-ref-resolver.ts` —— 复用，不改。
- 不要碰 `manifest.json` —— `scripting` 权限早就在了 from Wave 1。
- 不要在 SW 里手动注入 `a11y-tree.ts` —— 它已经通过 `content_scripts` manifest 条目自动注入到每个页面。
