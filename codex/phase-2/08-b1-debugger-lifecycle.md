# Codex Task 08 — Phase 2 B1：chrome.debugger 生命周期管理

> 你（Codex）要为 MateClaw 浏览器扩展实现 **chrome.debugger attach/detach 管理器**。
> 它是后续所有 CDP 动作（click / type / scroll / move_mouse）的物理基础。
> 关键产出：typed `SessionDetachedError`、DevTools 占用检测、per-tab session map。

## 项目背景

- 扩展 = MV3 service worker + sidepanel + content scripts。Phase 1 manifest 已包含 `nativeMessaging` + `sidePanel` + `storage` + `alarms` + `notifications`。Phase 2 即将加 `debugger` + `scripting`（C6 任务负责声明，你只需要 import）。
- 一个 chrome.debugger 会话**一旦另一个 DevTools 在同 tab 打开就会自动 detach**，且 chrome 不会主动告诉你为什么 — 必须监听 `chrome.debugger.onDetach`，根据 `reason` 区分。
- B1 是 Wave 1 的根基 — B2..B8 (具体动作处理器) 都依赖 `DebuggerManager.send(tabId, method, params)`。

阅读这几个文件先理清上下文：
- `mateclaw-extension/manifest.json`（Wave 1 之后会加 debugger 权限）
- `mateclaw-extension/src/shared/edge-protocol.ts`（envelope 类型，已含 v1.1）
- `mateclaw-extension/src/sw/native-bridge.ts`（NM 通信，可以参考其错误处理风格）

## 你要交付的 3 个文件

放在 `mateclaw-extension/src/sw/`（扩展 service worker 目录）。

### 1. `debugger-manager.ts`

```typescript
import { CDP } from './cdp-types' // 你自己定义；只导出我们用到的 method/param/result 形状

/**
 * Typed error thrown when a chrome.debugger session is detached
 * unexpectedly mid-action. Distinct subclasses encode `reason` for
 * upstream error mapping (NO_TARGET_TAB / DEVTOOLS_OPEN / SESSION_DETACHED).
 */
export class SessionDetachedError extends Error {
  constructor(public readonly tabId: number, public readonly reason: DetachReason) {
    super(`debugger session for tab ${tabId} detached: ${reason}`)
    this.name = 'SessionDetachedError'
  }
}

export type DetachReason =
  | 'target_closed'        // tab was closed → upstream: NO_TARGET_TAB
  | 'canceled_by_user'     // user opened DevTools → upstream: DEVTOOLS_OPEN
  | 'replaced_with_devtools' // alias of canceled_by_user on some Chrome versions
  | 'unknown'

export class DebuggerManager {
  /** Per-tab attached state. `null` = not attached. */
  private readonly sessions = new Map<number, AttachedSession>()

  constructor(private readonly chrome: typeof globalThis.chrome) {
    this.chrome.debugger.onDetach.addListener(this.#onDetach)
  }

  /**
   * Attach to a tab. Idempotent — re-attaching to a tab that's already
   * attached returns the existing session without re-issuing the CDP call.
   *
   * Throws SessionDetachedError if the attach fails (e.g. tab gone) so the
   * caller can map to NO_TARGET_TAB / DEVTOOLS_OPEN at the wire level.
   */
  async attach(tabId: number): Promise<void>

  /**
   * Detach from a tab. No-op if not attached. Never throws (we're cleaning up).
   */
  async detach(tabId: number): Promise<void>

  /**
   * Send a CDP command on the attached session. Throws SessionDetachedError
   * if the session is gone (so action handlers can bail out cleanly).
   */
  async send<M extends keyof CDP>(tabId: number, method: M, params: CDP[M]['params']): Promise<CDP[M]['result']>

  /** True if we have a live debugger session for this tab. */
  isAttached(tabId: number): boolean

  #onDetach = (source: chrome.debugger.Debuggee, reason: string) => {
    if (source.tabId == null) return
    const sess = this.sessions.get(source.tabId)
    if (!sess) return
    sess.detachReason = normalizeReason(reason)
    sess.pending.forEach(p => p.reject(new SessionDetachedError(source.tabId!, sess.detachReason!)))
    this.sessions.delete(source.tabId)
  }
}

interface AttachedSession {
  tabId: number
  /** in-flight CDP send() promises so we can reject them on onDetach */
  pending: Array<{ resolve: (v: unknown) => void; reject: (e: Error) => void }>
  detachReason?: DetachReason
}

function normalizeReason(raw: string): DetachReason {
  if (raw === 'target_closed') return 'target_closed'
  if (raw === 'canceled_by_user' || raw === 'replaced_with_devtools') return 'canceled_by_user'
  return 'unknown'
}
```

### 2. `cdp-types.ts`（最小化的 CDP 类型表）

只包含 Phase 2 B2-B8 真正会用到的 4-5 个 method。其余 Codex 不要瞎补。需要的 method 至少：
- `Page.navigate` — `{url, referrer?, transitionType?}` → `{frameId, loaderId, errorText?}`
- `Input.dispatchMouseEvent` — `{type, x, y, button, clickCount, ...}` → `{}`
- `Input.dispatchKeyEvent` — `{type, text?, key?, code?, ...}` → `{}`
- `Page.captureScreenshot` — `{format, quality?}` → `{data}`
- `Runtime.evaluate` — `{expression, awaitPromise?}` → `{result, exceptionDetails?}`

```typescript
export interface CDP {
  'Page.navigate': {
    params: { url: string; referrer?: string }
    result: { frameId: string; loaderId?: string; errorText?: string }
  }
  // ... 其余 4 个 method 按同形状定义
}
```

### 3. `debugger-manager.test.ts`

用 Vitest + 手工 mock 一个 fake `chrome.debugger` API（不要拉 sinon-chrome — 这个项目其他地方没用，加依赖代价大）。Mock 模板：

```typescript
function fakeChrome() {
  const detachListeners: Array<(source: chrome.debugger.Debuggee, reason: string) => void> = []
  const calls: Array<{ method: string; tabId: number; cdpMethod?: string; params?: unknown }> = []

  return {
    chrome: {
      debugger: {
        attach: vi.fn((target, version, cb) => { calls.push({method: 'attach', tabId: target.tabId!}); cb?.() }),
        detach: vi.fn((target, cb) => { calls.push({method: 'detach', tabId: target.tabId!}); cb?.() }),
        sendCommand: vi.fn((target, cdpMethod, params, cb) => {
          calls.push({method: 'send', tabId: target.tabId!, cdpMethod, params})
          cb?.({/* per-test stubbed result */})
        }),
        onDetach: { addListener: (fn: any) => detachListeners.push(fn) },
      },
      runtime: { lastError: undefined },
    } as unknown as typeof globalThis.chrome,
    triggerDetach: (tabId: number, reason: string) => {
      detachListeners.forEach(fn => fn({tabId} as any, reason))
    },
    calls,
  }
}
```

Required test cases:

```typescript
describe('DebuggerManager', () => {
  it('attach then detach calls chrome.debugger.attach + detach', async () => { /* */ })

  it('attach is idempotent — second call does not invoke chrome.debugger.attach again', async () => { /* */ })

  it('send forwards to chrome.debugger.sendCommand with correct method + params', async () => { /* */ })

  it('send on detached tab throws SessionDetachedError(reason=target_closed)', async () => {
    // attach, then triggerDetach(tabId, 'target_closed'), then expect send to throw
  })

  it('devtools open mid-send rejects pending sends with reason=canceled_by_user', async () => {
    // attach, start a send() that never resolves, triggerDetach(tabId, 'canceled_by_user')
    // — the await should reject with SessionDetachedError(reason='canceled_by_user').
  })

  it('isAttached returns false after onDetach fires', async () => { /* */ })

  it('replaced_with_devtools normalises to canceled_by_user', async () => {
    // Chrome 121+ emits 'replaced_with_devtools' instead of 'canceled_by_user';
    // both map to the same DetachReason so action handlers can unify the mapping.
  })

  it('chrome.runtime.lastError on attach throws meaningful error', async () => {
    // Simulate chrome.runtime.lastError = {message: 'Cannot attach'} during attach;
    // expect the promise to reject (not silently no-op).
  })
})
```

### 测试运行

```
cd mateclaw-extension && pnpm test --run debugger-manager
```

期待最终 `Test Files 1 passed, Tests 8 passed`。

## TDD 步骤

1. 先写 `debugger-manager.test.ts`（包含全部 8 个测试）；编译失败（DebuggerManager 不存在）。
2. `pnpm test --run debugger-manager` → 报 `Cannot find module './debugger-manager'`。
3. 实现 `debugger-manager.ts` + `cdp-types.ts`。
4. `pnpm test --run debugger-manager` → 全绿。
5. Commit。

## Commit message template

```
feat(extension): chrome.debugger lifecycle manager (B1)

- DebuggerManager.attach/detach/send with per-tab session map
- typed SessionDetachedError carrying DetachReason
  ('target_closed' | 'canceled_by_user' | 'replaced_with_devtools' → unified | 'unknown')
- chrome.debugger.onDetach handler rejects all in-flight sends on detach
- attach is idempotent; detach is no-op when not attached
- 8 debugger-manager.test cases covering attach idempotency, devtools-open,
  tab-close, chrome.runtime.lastError propagation

Phase 2 Wave 1 — task B1.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要实现具体动作 (click/type/scroll/move_mouse/navigate/wait) — 那是 B2-B8（Wave 2）的工作。这一波只交付 `DebuggerManager.send()` 的能力。
- 不要碰 manifest.json — debugger 权限由 C6（Sub-agent B，并行运行）声明。
- 不要碰 `src/shared/edge-protocol.ts` — Wave 0 已固定。
- 不要拉新的 npm 依赖 — `pnpm test` 必须用现有 deps 跑通。
