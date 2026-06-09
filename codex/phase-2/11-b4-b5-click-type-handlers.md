# Codex Task 11 — Phase 2 B4 + B5：click + type action handlers (CDP Input 家族)

> 你（Codex）要实现两个紧密相关的处理器，**在一个 prompt 里一起做**因为它们
> 共用同一套 CDP `Input.dispatchMouseEvent` / `Input.dispatchKeyEvent` 模式：
> - **B4 click** — 在坐标 (x, y) 上发送 mousePressed + mouseReleased 对，可选
>   双击 / 三击 / 右键。
> - **B5 type** — 把 `text` 拆成字符序列，逐个发送 keyDown + (text-as-input) +
>   keyUp。可选先 click(focus_target) 把焦点放到目标元素。

两个处理器**必须支持 clock 和 RNG 注入**（用于 stable 测试时序 + 模拟人类输入抖动）。

## 项目背景

阅读这些文件先理清上下文：
- `mateclaw-extension/src/sw/debugger-manager.ts`（DebuggerManager.send(tabId, method, params)）
- `mateclaw-extension/src/sw/cdp-types.ts`（已含 `Input.dispatchMouseEvent` + `Input.dispatchKeyEvent` 类型）
- `mateclaw-extension/src/sw/action/types.ts`（`ClickParams`, `TypeParams`, `ActionFailureError`）
- `mateclaw-extension/src/sw/action/ActionExecutor.ts`（handler 签名 + ActionFailureError）

## 你要交付的 4 个文件

### 1. `mateclaw-extension/src/sw/action/handlers/click.ts`

```typescript
import type { ActionHandler, ClickParams } from '../types'
import { ActionFailureError } from '../ActionExecutor'
import type { DebuggerManager } from '../../debugger-manager'

export interface ClickHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number          // default: Date.now
  random?: () => number         // default: Math.random
  /** Extra micro-delay before mouseReleased (default: 30-100ms log-normal) */
  pressHoldMs?: () => number
}

/**
 * click handler.
 *
 * Flow:
 *   1. debugger.attach(tabId) — idempotent.
 *   2. For each click in 1..click_count:
 *      a. send Input.dispatchMouseEvent { type: 'mousePressed', x, y, button, clickCount }
 *      b. wait pressHoldMs() (default: log-normal ~50ms — human finger delay)
 *      c. send Input.dispatchMouseEvent { type: 'mouseReleased', x, y, button, clickCount }
 *      d. if not last click, wait ~30-150ms between clicks (double/triple-click intervals)
 *   3. return Success.
 *
 * Throws ActionFailureError('SESSION_DETACHED') if DebuggerManager.send throws
 * SessionDetachedError (e.g. DevTools opened mid-click).
 */
export const clickHandler = (deps: ClickHandlerDeps): ActionHandler<ClickParams> => {
  return async (tabId, params, deadlineMs) => {
    // implementation
  }
}
```

### 2. `mateclaw-extension/src/sw/action/handlers/click.test.ts`

Required tests (10+):

```typescript
describe('click handler', () => {
  it('single left click sends mousePressed + mouseReleased at (x,y)', async () => {})

  it('right-button click sets button=right on both events', async () => {})

  it('middle-button click sets button=middle', async () => {})

  it('double-click sends 2× (pressed,released) with clickCount escalating 1→2', async () => {
    // CDP semantics: second press has clickCount=2 (so the page sees dblclick event)
  })

  it('triple-click sends 3× with clickCount 1→2→3', async () => {})

  it('uses injected clock + random for deterministic hold times', async () => {
    // Given clock=fixed, random=fixed → pressHoldMs returns deterministic value
    // → the test can assert the elapsed timing between pressed and released.
  })

  it('throws SESSION_DETACHED when DebuggerManager.send throws SessionDetachedError', async () => {})

  it('default button=left when not specified', async () => {})

  it('default click_count=1 when not specified', async () => {})

  it('attaches to tab before first event', async () => {
    // debugger.attach was called exactly once before any dispatchMouseEvent
  })
})
```

### 3. `mateclaw-extension/src/sw/action/handlers/type.ts`

```typescript
import type { ActionHandler, TypeParams } from '../types'
import { ActionFailureError } from '../ActionExecutor'
import type { DebuggerManager } from '../../debugger-manager'
import { clickHandler } from './click'

export interface TypeHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number
  random?: () => number
  /** Per-keystroke delay (default: log-normal ~40-120ms — human typing) */
  keystrokeIntervalMs?: () => number
}

/**
 * type handler.
 *
 * Flow:
 *   1. debugger.attach(tabId).
 *   2. If params.focus_target → click that point first (single left click).
 *   3. For each char in params.text:
 *      a. send Input.dispatchKeyEvent { type: 'keyDown', text: char, key: char, ... }
 *      b. send Input.dispatchKeyEvent { type: 'char', text: char, key: char, ... }
 *         (Some characters require the 'char' event for IME — keep it simple
 *         and always send keyDown + char + keyUp.)
 *      c. send Input.dispatchKeyEvent { type: 'keyUp', text: char, key: char, ... }
 *      d. wait keystrokeIntervalMs() (default: log-normal ~70ms)
 *   4. return Success with { chars_typed: text.length }.
 */
export const typeHandler = (deps: TypeHandlerDeps): ActionHandler<TypeParams> => {
  return async (tabId, params, deadlineMs) => {
    // implementation
  }
}
```

### 4. `mateclaw-extension/src/sw/action/handlers/type.test.ts`

Required tests (8+):

```typescript
describe('type handler', () => {
  it('types "abc" sends 3× (keyDown + char + keyUp) cycles', async () => {})

  it('with focus_target sends a left click first, then keys', async () => {
    // First CDP call is dispatchMouseEvent mousePressed at focus_target.{x,y}
  })

  it('without focus_target sends only key events', async () => {})

  it('returns Success with chars_typed = text.length', async () => {})

  it('empty text returns Success with chars_typed=0 and no CDP calls', async () => {})

  it('uses injected clock+random for deterministic keystroke intervals', async () => {})

  it('SESSION_DETACHED during typing throws ActionFailureError', async () => {})

  it('handles unicode characters (text includes 你好)', async () => {
    // Verify the char-type event has text: '你' (not surrogate-pair split or empty)
  })

  it('handles special keys via Enter / Tab / Backspace literal in text', async () => {
    // For B5 we keep it simple — treat \n as Enter (key='Enter'), \t as Tab, \b as Backspace
  })
})
```

Test fixture (shared mock for both files):

```typescript
function fakeDebugger() {
  const sent: Array<{ tabId: number; method: string; params: any }> = []
  const debuggerStub = {
    attach: vi.fn(async () => {}),
    detach: vi.fn(async () => {}),
    send: vi.fn(async (tabId: number, method: string, params: any) => {
      sent.push({ tabId, method, params })
      return {}
    }),
    isAttached: () => true,
  } as unknown as DebuggerManager

  return { debuggerStub, sent }
}
```

## TDD 步骤

1. 先写 `click.test.ts`，编译失败。
2. `pnpm test --run click.test` → fail。
3. 实现 `click.ts`。
4. `pnpm test --run click.test` → 全绿。
5. 同样流程做 `type.ts`（type 会 import click — 测试时可以 mock 或注入 click 的实现）。
6. 两个文件一次性 commit（它们紧耦合）。

## Commit message template

```
feat(extension): click + type action handlers (B4+B5)

Both built on chrome.debugger + CDP Input.dispatch{Mouse,Key}Event:

- click: mousePressed + (log-normal hold) + mouseReleased pairs, supports
  clickCount 1..N (double/triple-click via CDP's escalating clickCount)
- type: optional focus click first, then per-char keyDown+char+keyUp
  cycles with log-normal interval between strokes
- Both accept injected clock+random for deterministic test timing
- SESSION_DETACHED bubbles up from DebuggerManager as typed wire error

19 vitest cases between the two files. Unicode + special keys covered.

Phase 2 Wave 2 — tasks B4 + B5.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要碰 `ActionExecutor.ts` / `types.ts` / `cdp-types.ts`（已固定）。
- 不要碰其他 handler 文件（B3/B6/B7/B8 并行）。
- 不要用 `chrome.scripting.executeScript` 注入 DOM — 全部走 CDP。
- 不要用 real `setTimeout(...)` — 用注入的 clock + 异步 promise 模拟时间。
