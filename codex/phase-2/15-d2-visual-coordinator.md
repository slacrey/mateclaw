# Codex Task 15 — Phase 2 D2：VisualCoordinator (SW)

> 你（Codex）要实现 SW 端的视觉指示器协调器。当 Control Plane 通过 NM bridge
> 下发 `indicator.show` / `indicator.hide` / `indicator.cursor` /
> `indicator.tool_use_hide` / `indicator.tool_use_show` envelope 时，
> SW 解析 `tab_ref` → 具体 tab id，然后通过 `chrome.tabs.sendMessage(tabId, ...)`
> 转发给 visual-indicator 内容脚本（C5，Sub-agent E 并行交付）。

## 项目背景

阅读这些文件先理清上下文：
- `mateclaw-extension/src/sw/action/action-router.ts` —— B10 的 ActionRouter，结构可参考
- `mateclaw-extension/src/sw/action/tab-ref-resolver.ts` —— 已存在，直接复用
- `mateclaw-extension/src/shared/edge-protocol.ts` —— v1.1 已定义全部 indicator.* 枚举
- `docs/specs/edge-protocol.md` §"indicator.show / hide / cursor / tool_use_hide / tool_use_show"

## VisualCoordinator 角色边界

**不**是 action handler（不进 ActionExecutor）。
**不**响应 `indicator.stop_clicked` —— 那是 Ext → CP 方向，由 C5 内容脚本生成、走 ActionRouter（B10 已实现）上行，**不经过 D2**。

D2 只管 **CP → Ext 方向** 的 5 种 indicator.* envelope，转发到内容脚本。

## 内部消息协议（VisualCoordinator → C5）

VisualCoordinator 通过 `chrome.tabs.sendMessage(tabId, { type: '<TYPE>', ...payload })` 把指令送到 visual-indicator 内容脚本。**注意：这是浏览器内部的扩展消息（chrome.runtime/tabs.sendMessage），不是 Edge protocol envelope**。约定如下：

| Edge wire kind | 内部 chrome message type | 透传 payload |
|---|---|---|
| `indicator.show` | `SHOW_AGENT_INDICATORS` | `{ isMcp?: boolean }` |
| `indicator.hide` | `HIDE_AGENT_INDICATORS` | `{}` |
| `indicator.cursor` | `INDICATOR_CURSOR` | `{ x, y }` |
| `indicator.tool_use_hide` | `TOOL_USE_HIDE` | `{}` |
| `indicator.tool_use_show` | `TOOL_USE_SHOW` | `{}` |

C5（并行做的）会在 `chrome.runtime.onMessage` 监听这些类型并把指令分发到 PhantomCursor / GlowBorder / StopButton。

## 你要交付的 2 个文件 + 1 处 SW wiring

### 1. `mateclaw-extension/src/sw/visual-coordinator.ts`

```typescript
import type { EdgeMessage } from '../shared/edge-protocol'
import { EdgeMessageKind } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'

export interface VisualCoordinatorDeps {
  resolver: TabRefResolver
  /** Chrome API; injectable for tests. */
  chrome?: typeof globalThis.chrome
}

/**
 * Forwards CP → Ext indicator.* envelopes to the visual-indicator
 * content script via chrome.tabs.sendMessage.
 *
 * Flow:
 *   1. Read payload.tab_ref → resolver.resolve(tab_ref) → tabId or null.
 *   2. null → log warning, drop the envelope silently (no upstream
 *      response — these are fire-and-forget per the spec, except
 *      indicator.cursor which has a result; see below).
 *   3. Map wire kind → internal message type (see table in this brief).
 *   4. chrome.tabs.sendMessage(tabId, internalMessage).
 *   5. For indicator.cursor specifically, the content script returns
 *      { ok: true, arrived_at_ms } via the sendResponse callback. The
 *      coordinator does NOT need to forward this upstream — the CP polls
 *      for arrival via a subsequent a11y.snapshot.request. Drop the
 *      response. (Yes, the spec says indicator.cursor has a result on
 *      action.result — but the result is emitted by the content script
 *      directly upward through the bridge in a separate envelope. The
 *      coordinator just delivers the request.)
 *
 *      WAIT — re-read spec §"indicator.cursor". The result IS supposed
 *      to come back. We have two options:
 *        (a) coordinator emits the action.result envelope itself
 *            after sendMessage resolves with the response object
 *        (b) content script sends the action.result via its own
 *            chrome.runtime.sendMessage path back through the SW
 *      For Phase 2 D2 simplicity, pick (a) — the coordinator owns the
 *      round-trip: sendMessage resolves with { ok: true, arrived_at_ms,
 *      msg_id_echo }, coordinator wraps in EdgeMessage(kind=action.result,
 *      in_reply_to=msg_id_echo) and calls sendUp.
 *
 *      Document the choice in a comment block; the auditor reading this
 *      file will appreciate clarity.
 */
export class VisualCoordinator {
  constructor(private readonly deps: VisualCoordinatorDeps) {}

  async handle(msg: EdgeMessage): Promise<void>

  /** True if this kind should be routed by D2 (not by ActionRouter). */
  static handles(kind: EdgeMessageKind): boolean {
    return (
      kind === EdgeMessageKind.IndicatorShow ||
      kind === EdgeMessageKind.IndicatorHide ||
      kind === EdgeMessageKind.IndicatorCursor ||
      kind === EdgeMessageKind.IndicatorToolUseHide ||
      kind === EdgeMessageKind.IndicatorToolUseShow
    )
  }
}
```

If you want to add a `sendUp` for the `indicator.cursor` round-trip, add it to `VisualCoordinatorDeps` (matching ActionRouter's pattern).

### 2. `mateclaw-extension/src/sw/visual-coordinator.test.ts`

12+ tests:

```typescript
describe('VisualCoordinator', () => {
  it('indicator.show with tab_ref="main" forwards SHOW_AGENT_INDICATORS to resolved tab', async () => {
    // resolver.resolve('main') → 42
    // chrome.tabs.sendMessage called with (42, { type: 'SHOW_AGENT_INDICATORS', isMcp: undefined })
  })

  it('indicator.show with isMcp=true preserves the flag in the forwarded message', async () => {})

  it('indicator.hide forwards HIDE_AGENT_INDICATORS', async () => {})

  it('indicator.cursor forwards INDICATOR_CURSOR with x,y', async () => {})

  it('indicator.tool_use_hide forwards TOOL_USE_HIDE', async () => {})

  it('indicator.tool_use_show forwards TOOL_USE_SHOW', async () => {})

  it('unresolvable tab_ref drops the envelope (no chrome.tabs.sendMessage call)', async () => {})

  it('integer tab_ref passes through verbatim', async () => {})

  it('indicator.cursor sendMessage response → coordinator emits action.result upstream', async () => {
    // chrome.tabs.sendMessage resolves with { ok: true, arrived_at_ms: 123 }
    // coordinator calls sendUp with kind=action.result, in_reply_to=msg.msg_id,
    // payload.ok=true, payload.elapsed_ms=...
  })

  it('VisualCoordinator.handles() returns true for the 5 indicator.* kinds and false for action.execute / a11y.snapshot.request / etc.', () => {})

  it('chrome.tabs.sendMessage rejection (tab gone) is caught and logged, does not throw', async () => {})

  it('session_id="" preserved on the action.result round-trip from indicator.cursor', async () => {})
})
```

### 3. Update `mateclaw-extension/src/sw/index.ts`

Add after the SnapshotRequestHandler wiring (if B11 lands first) or after the ActionRouter (if not):

```typescript
import { VisualCoordinator } from './visual-coordinator'

const visualCoordinator = new VisualCoordinator({
  resolver,
  // chrome defaults
})

// Inside bridge.onMessage:
if (VisualCoordinator.handles(m.kind)) {
  visualCoordinator.handle(m).catch(e => {
    console.error('[mateclaw][sw] VisualCoordinator.handle threw', e)
  })
}
```

Use the static `.handles()` predicate to keep the dispatch in index.ts tight (no kind-by-kind if/else stack).

## TDD 步骤

1. 写 test file → fail.
2. 实现 visual-coordinator.ts → green.
3. Wire into sw/index.ts.
4. `pnpm test --run` 全套确认无回归（168 + 14 ≈ 182 个测试）。
5. Commit.

## Commit message template

```
feat(extension): VisualCoordinator — CP→Ext indicator.* routing (D2)

SW receives the five indicator.* envelopes from the NM bridge, resolves
tab_ref via the shared TabRefResolver, and forwards each one to the
visual-indicator content script (C5) via chrome.tabs.sendMessage with
mapped internal-message types:
  indicator.show           → SHOW_AGENT_INDICATORS
  indicator.hide           → HIDE_AGENT_INDICATORS
  indicator.cursor         → INDICATOR_CURSOR { x, y }
  indicator.tool_use_hide  → TOOL_USE_HIDE
  indicator.tool_use_show  → TOOL_USE_SHOW

indicator.cursor round-trips: content script's sendMessage response
contains { ok: true, arrived_at_ms } → coordinator wraps in action.result
EdgeMessage with in_reply_to and calls sendUp.

VisualCoordinator.handles(kind) static predicate keeps SW dispatch clean.

~14 vitest cases incl. tab resolution + 5 kind mapping + sendMessage
rejection handling + action.result round-trip envelope invariants.

sw/index.ts gains the bridge.onMessage VisualCoordinator branch.

Phase 2 Wave 3 — task D2.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要碰 ActionRouter —— D2 是平行 router，不进 ActionRouter（语义不同）。
- 不要碰 `indicator.stop_clicked` —— Ext→CP 方向已在 B10 ActionRouter 实现。
- 不要碰 visual-indicator 内容脚本（C5）—— 那是 Sub-agent E 并行做的。
- 不要碰 manifest.json —— 不需要新权限。
- 不要在 SW 里直接操作 DOM —— SW 没有 DOM access。所有视觉操作必须经 chrome.tabs.sendMessage 转发到 C5。
