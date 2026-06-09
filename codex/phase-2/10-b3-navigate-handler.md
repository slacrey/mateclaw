# Codex Task 10 — Phase 2 B3：navigate action handler

> 你（Codex）要实现 `navigate` 动作处理器：用 `chrome.tabs.update()` 切顶层
> URL，等到 `chrome.webNavigation.onCompleted`（或对应 wait_for 策略）触发后
> 返回 `ActionResult.Success`。带超时（race with `deadline_ms`）。

## 项目背景

阅读这两个文件先理清上下文：
- `mateclaw-extension/src/sw/action/ActionExecutor.ts`（B2 派发壳，已交付）
- `mateclaw-extension/src/sw/action/types.ts`（强类型 `NavigateParams` + `ActionFailureError`）

`ActionHandler<NavigateParams>` 签名：
```typescript
(tabId: number, params: NavigateParams, deadlineMs: number) => Promise<ActionResult>
```

`NavigateParams`：
```typescript
{ url: string; referer?: string; wait_for?: 'load' | 'domcontentloaded' | 'network_idle' | 'none' }
```

成功时返回 `{ ok: true, elapsed_ms: 0, payload: { final_url, http_status?, load_state } }`。
（注：executor 会覆盖 elapsed_ms 为 wall-clock 测量，handler 给 0 即可。）

## 你要交付的 2 个文件

### 1. `mateclaw-extension/src/sw/action/handlers/navigate.ts`

```typescript
import type { ActionHandler, NavigateParams } from '../types'
import { ActionFailureError } from '../ActionExecutor'

/**
 * navigate handler.
 *
 * Flow:
 *   1. chrome.tabs.update(tabId, { url, ... }) → triggers navigation.
 *   2. Race three signals:
 *        (a) chrome.webNavigation.onCompleted for tabId+frameId=0 → success.
 *            For wait_for='domcontentloaded', use onDOMContentLoaded instead.
 *            For wait_for='network_idle', also wait for chrome.webNavigation
 *            .onCompleted + a 500ms quiet window with no further
 *            chrome.webRequest activity (or as close as we can with MV3).
 *            For wait_for='none', return immediately after tabs.update().
 *        (b) deadlineMs timer → throw ActionFailureError('TIMEOUT_PAGE_LOAD', ...).
 *        (c) chrome.tabs.onRemoved for tabId → throw ActionFailureError('NO_TARGET_TAB', ...).
 *   3. Read final URL via chrome.tabs.get(tabId) → return payload.
 *
 * Listeners MUST be removed in finally{} — leaked listeners pile up
 * across MV3 SW restarts and silently break later actions.
 */
export const navigateHandler = (
  chromeApi: typeof globalThis.chrome = chrome,
): ActionHandler<NavigateParams> => {
  return async (tabId, params, deadlineMs) => {
    // implementation here
  }
}
```

The factory-with-injectable-chrome pattern is REQUIRED for testability —
the test file uses a fake chrome. **Do NOT call the global `chrome.*`
directly inside the handler body** — always reference `chromeApi`.

### 2. `mateclaw-extension/src/sw/action/handlers/navigate.test.ts`

Required test cases (8+):

```typescript
describe('navigate handler', () => {
  it('happy path with wait_for=load → returns Success with final_url', async () => {})

  it('wait_for=domcontentloaded uses onDOMContentLoaded listener', async () => {})

  it('wait_for=network_idle waits for completed + 500ms idle window', async () => {})

  it('wait_for=none returns immediately after tabs.update', async () => {})

  it('deadline expiry → throws ActionFailureError(TIMEOUT_PAGE_LOAD, retryable=true)', async () => {
    // Simulate: tabs.update returns; but webNavigation.onCompleted NEVER fires;
    // after deadlineMs, the handler must throw with the typed code.
    // (Use vi.useFakeTimers() + vi.advanceTimersByTimeAsync(deadlineMs).)
  })

  it('tab closed mid-navigation → throws ActionFailureError(NO_TARGET_TAB)', async () => {})

  it('chrome.tabs.update rejection → throws ActionFailureError(NO_TARGET_TAB)', async () => {
    // e.g. tab id doesn't exist
  })

  it('iframe load (frameId != 0) is ignored', async () => {
    // triggerWebNav({tabId, frameId: 99}) does NOT resolve the handler
  })

  it('handler removes all chrome listeners on success', async () => {
    // After resolution, the fakeChrome listener arrays must shrink back to 0.
  })

  it('handler removes all chrome listeners on failure (timeout)', async () => {
    // Same invariant on failure path.
  })

  it('handler removes all chrome listeners on tab-removed', async () => {})
})
```

Test fixture style — mirror Codex 09's `tab-group-manager.test.ts`:

```typescript
function fakeChrome() {
  const completedListeners: any[] = []
  const dclListeners: any[] = []
  const removedListeners: any[] = []
  const tabsState = new Map<number, chrome.tabs.Tab>()

  return {
    chrome: {
      tabs: {
        update: vi.fn(async (tabId: number, props: chrome.tabs.UpdateProperties) => {
          if (!tabsState.has(tabId)) throw new Error('No tab with id: ' + tabId)
          // simulate navigation start; URL becomes the new URL but not yet loaded
        }),
        get: vi.fn(async (tabId: number) => tabsState.get(tabId)),
        onRemoved: { addListener: (fn: any) => removedListeners.push(fn), removeListener: (fn: any) => { /* splice */ } },
      },
      webNavigation: {
        onCompleted: { addListener: (fn: any) => completedListeners.push(fn), removeListener: (fn: any) => { /* splice */ } },
        onDOMContentLoaded: { addListener: (fn: any) => dclListeners.push(fn), removeListener: (fn: any) => { /* splice */ } },
      },
    } as unknown as typeof globalThis.chrome,
    triggerCompleted: (tabId: number, frameId: number, url: string) => {
      completedListeners.forEach(fn => fn({ tabId, frameId, url }))
    },
    triggerDCL: (tabId: number, frameId: number) => dclListeners.forEach(fn => fn({ tabId, frameId })),
    triggerTabRemoved: (tabId: number) => removedListeners.forEach(fn => fn(tabId, {})),
    setTabState: (tab: chrome.tabs.Tab) => tabsState.set(tab.id!, tab),
    listenerCounts: () => ({ completed: completedListeners.length, dcl: dclListeners.length, removed: removedListeners.length }),
  }
}
```

## TDD 步骤

1. 先写 `navigate.test.ts`，全 fail / no-module。
2. `pnpm test --run navigate.test`. 报 `Cannot find module './navigate'`。
3. 实现 `navigate.ts`。
4. `pnpm test --run navigate.test`. 全绿。
5. Commit.

## Commit message template

```
feat(extension): navigate action handler (B3)

chrome.tabs.update for top-level navigation, races webNavigation.onCompleted
(or onDOMContentLoaded for wait_for=domcontentloaded) against the
deadline_ms timeout. Tab-closed during navigation surfaces as NO_TARGET_TAB.

- iframe loads (frameId != 0) are filtered out of the completion check
- network_idle adds a 500ms quiet window after completed fires
- ALL chrome listeners removed in finally{} — no leaked listeners
  surviving SW restarts

11 navigate.test cases covering 4 wait_for strategies × success +
deadline + tab-removed + listener-cleanup invariants.

Phase 2 Wave 2 — task B3.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要碰 `ActionExecutor.ts` 或 `types.ts`（B2 已固定）。
- 不要碰其他 handler 文件（B4-B8 在并行运行）。
- 不要碰 `manifest.json`（permissions 已就位 from Wave 1）。
- 不要用 CDP — `chrome.tabs.update` 是更稳的顶层导航路径，CDP 留给 click/type/scroll/move_mouse。
