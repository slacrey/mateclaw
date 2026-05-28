# Codex Task 12 — Phase 2 B6：scroll action handler

> 你（Codex）要实现 `scroll` 处理器：用 CDP `Input.dispatchMouseWheelEvent`
> 在视口中央滚动指定距离，分若干 segments 平滑发送（不是一次性大滚），
> segment 之间用 log-normal 时延，模拟人类用力推滚轮的节奏。

## 项目背景

阅读这些文件先理清上下文：
- `mateclaw-extension/src/sw/debugger-manager.ts`
- `mateclaw-extension/src/sw/cdp-types.ts`（需要扩展 `Input.dispatchMouseWheelEvent`，见下）
- `mateclaw-extension/src/sw/action/types.ts`（`ScrollParams`）

## 你要交付的 3 个文件

### 1. `mateclaw-extension/src/sw/cdp-types.ts` 扩展

打开现有文件，给 `CDP` 接口加一行：

```typescript
'Input.dispatchMouseWheelEvent': {
  params: {
    type: 'mouseWheel'
    x: number
    y: number
    deltaX: number
    deltaY: number
    /** Pointer modifiers; we always send 0 for now. */
    modifiers?: number
  }
  result: {}
}
```

不要删除已有的 entries（B1 + B4/B5 都依赖它们）。

### 2. `mateclaw-extension/src/sw/action/handlers/scroll.ts`

```typescript
import type { ActionHandler, ScrollParams } from '../types'
import { ActionFailureError } from '../ActionExecutor'
import type { DebuggerManager } from '../../debugger-manager'

export interface ScrollHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number
  random?: () => number
  /** Per-segment delay (default: log-normal ~60-180ms — wheel push cadence) */
  segmentIntervalMs?: () => number
  /** Returns the viewport center for the current tab (defaults to a static
   *  midpoint; real impl can use chrome.tabs.get + chrome.action.getZoom, but
   *  the executor doesn't have to be pixel-perfect — the page coords don't
   *  matter for wheel events, only deltas). */
  viewportCenter?: (tabId: number) => Promise<{ x: number; y: number }>
}

/**
 * scroll handler.
 *
 * Params: { direction: 'up'|'down'|'left'|'right', distance_px: number, segments?: number }
 *
 * Flow:
 *   1. debugger.attach(tabId).
 *   2. center = viewportCenter(tabId) (default to {640, 400} if not given).
 *   3. segments = params.segments ?? 5.
 *   4. delta-per-segment = distance_px / segments, signed by direction:
 *        up    → deltaY = -d
 *        down  → deltaY = +d
 *        left  → deltaX = -d
 *        right → deltaX = +d
 *   5. For i in 0..segments-1:
 *      a. send Input.dispatchMouseWheelEvent { type:'mouseWheel', x, y, deltaX, deltaY }
 *      b. if i < segments-1, wait segmentIntervalMs()
 *   6. return Success.
 *
 * Throws ActionFailureError('SESSION_DETACHED') on detach.
 */
export const scrollHandler = (deps: ScrollHandlerDeps): ActionHandler<ScrollParams> => {
  return async (tabId, params, deadlineMs) => {
    // implementation
  }
}
```

### 3. `mateclaw-extension/src/sw/action/handlers/scroll.test.ts`

Required tests (8+):

```typescript
describe('scroll handler', () => {
  it('scroll down 500px in 5 segments sends 5 wheel events with deltaY=100', async () => {})

  it('scroll up 500px sends 5 wheel events with deltaY=-100', async () => {})

  it('scroll right sends deltaX positive, deltaY=0', async () => {})

  it('scroll left sends deltaX negative, deltaY=0', async () => {})

  it('default segments=5 when not specified', async () => {})

  it('uses injected clock + random for deterministic segment timing', async () => {})

  it('SESSION_DETACHED throws typed error', async () => {})

  it('segments=1 sends one large wheel event with full distance_px', async () => {
    // Edge case: caller wants a snap scroll.
  })

  it('rounds delta-per-segment to integers (CDP rejects float deltas on some Chrome versions)', async () => {
    // distance_px=7, segments=3 → segments should still produce integer deltas summing to ~7
    // (e.g. 2, 2, 3 OR 3, 2, 2 — implementation choice, but every delta must be integer)
  })
})
```

## TDD 步骤

1. 写测试 → 编译失败。
2. 运行 → fail。
3. 实现。
4. 运行 → 全绿。
5. Commit（一起 commit cdp-types.ts + scroll.ts + scroll.test.ts）。

## Commit message template

```
feat(extension): scroll action handler (B6)

Segmented CDP Input.dispatchMouseWheelEvent — splits distance_px into N
segments (default 5) and sends them with log-normal inter-segment
delays to mimic a human wheel-push rhythm. Integer-delta rounding
absorbs distance_px that doesn't evenly divide.

- cdp-types.ts gains the Input.dispatchMouseWheelEvent entry
- 9 scroll.test cases incl. 4 directions, default segments,
  injected clock/random, SESSION_DETACHED unwrap, snap-scroll edge case,
  integer-delta rounding

Phase 2 Wave 2 — task B6.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要 expand cdp-types.ts 其他无关 method —— 只加 dispatchMouseWheelEvent 一条。
- 不要碰其他 handler 文件（B3/B4/B5/B7/B8 并行）。
- 不要用 `chrome.scripting.executeScript` 注入 `window.scrollBy()` —— 走 CDP wheel events 保持架构一致。
