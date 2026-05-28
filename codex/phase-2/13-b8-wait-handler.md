# Codex Task 13 — Phase 2 B8：wait action handler

> 你（Codex）要实现 `wait` 处理器：根据 `strategy` 字段选择 3 种等待方式之一：
> - **`time`** — 简单等 `duration_ms` 后返回。
> - **`network_idle`** — 监听 `chrome.webRequest.onCompleted` / `.onErrorOccurred`，
>   当 `idle_threshold_ms`（默认 500ms）内没有任何新请求时返回。
> - **`load_state`** — 等到 `chrome.webNavigation.onCompleted` 或对应的 readyState
>   信号（`load_state: load|domcontentloaded|network_idle`）。

返回 `{ ok: true, payload: { waited_ms: <实际等待时长> } }`。

## 项目背景

阅读这些文件先理清上下文：
- `mateclaw-extension/src/sw/action/types.ts` —— `WaitParams`：`{ strategy, duration_ms?, idle_threshold_ms?, load_state? }`
- `mateclaw-extension/src/sw/action/ActionExecutor.ts` —— handler 签名 + `ActionFailureError`

**注意 P0 invariant**：`chrome.webRequest` 在 MV3 默认是只读的，且需要 `webRequest` permission。
**不要修改 manifest.json**——network_idle 策略可以用一个降级实现：用一个轮询的
`setTimeout` 检查 `performance.getEntriesByType('resource')` 的 length 变化（content
script 注入），或者更简单：先实现 `time` + `load_state` 两个策略，network_idle 用
fallback 退化到 idle_threshold_ms 的纯 timer（FIXME 注释标 Phase 3）。**优先选择
简单可测的实现，不要追求物理精确**。

## 你要交付的 2 个文件

### 1. `mateclaw-extension/src/sw/action/handlers/wait.ts`

```typescript
import type { ActionHandler, WaitParams } from '../types'
import { ActionFailureError } from '../ActionExecutor'

export interface WaitHandlerDeps {
  /** Injectable clock — defaults to Date.now. */
  clock?: () => number
  /** Promise-returning sleeper — defaults to setTimeout-based. Tests can stub. */
  sleep?: (ms: number) => Promise<void>
  /** chrome API (for load_state strategy). Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
}

/**
 * wait handler.
 *
 * - strategy='time'        → sleep(duration_ms) → Success(waited_ms = duration_ms).
 *                             duration_ms required; throws ActionFailureError('VALIDATION') if missing.
 * - strategy='load_state'  → register chrome.webNavigation listener for the
 *                             requested state (load → onCompleted,
 *                             domcontentloaded → onDOMContentLoaded,
 *                             network_idle → onCompleted + 500ms idle window),
 *                             race against deadlineMs. Returns elapsed wall-clock.
 *                             Throws ActionFailureError('TIMEOUT_PAGE_LOAD') on deadline.
 * - strategy='network_idle' → Phase-2 fallback: sleep(idle_threshold_ms ?? 500)
 *                             then return. Tagged with a FIXME for Phase 3 to
 *                             swap in real webRequest monitoring.
 *
 * All paths return Success with payload.waited_ms = clock()-start.
 */
export const waitHandler = (deps: WaitHandlerDeps = {}): ActionHandler<WaitParams> => {
  return async (tabId, params, deadlineMs) => {
    // implementation
  }
}
```

### 2. `mateclaw-extension/src/sw/action/handlers/wait.test.ts`

Required tests (10+):

```typescript
describe('wait handler', () => {
  it('strategy=time + duration_ms=500 returns Success(waited_ms ~500)', async () => {
    // Use vi.useFakeTimers() and stub sleep to advance time deterministically
  })

  it('strategy=time without duration_ms throws ActionFailureError(VALIDATION)', async () => {})

  it('strategy=time with duration_ms exceeding deadline_ms throws TIMEOUT_PAGE_LOAD', async () => {
    // params.duration_ms=10000, deadlineMs=5000 → must reject before completing
  })

  it('strategy=load_state load → resolves when webNavigation.onCompleted fires for tabId+frameId=0', async () => {})

  it('strategy=load_state domcontentloaded → resolves on onDOMContentLoaded', async () => {})

  it('strategy=load_state never fires → timeout at deadline_ms → TIMEOUT_PAGE_LOAD', async () => {})

  it('strategy=load_state iframe (frameId != 0) does NOT resolve', async () => {})

  it('strategy=network_idle fallback: sleep idle_threshold_ms then return', async () => {
    // Use injected sleep stub — must have been called with the right ms value
  })

  it('strategy=network_idle default threshold = 500ms when not specified', async () => {})

  it('removes all chrome listeners in finally on success and on failure', async () => {})

  it('waited_ms accurately reflects wall-clock from start to completion', async () => {
    // Inject clock=increasing-counter; assert payload.waited_ms = exit_clock - entry_clock
  })

  it('invalid strategy value throws ActionFailureError(VALIDATION)', async () => {
    // Pass strategy='invalid' as any — must reject upfront, not after deadline
  })
})
```

Test fixture follows the same fakeChrome pattern as B3.

## TDD 步骤

1. 写测试 → fail。
2. 实现 → green。
3. Commit。

## Commit message template

```
feat(extension): wait action handler (B8)

Three strategies:
  time         → sleep(duration_ms) — bounded by deadline_ms
  load_state   → race chrome.webNavigation listener vs deadline; throws
                 TIMEOUT_PAGE_LOAD on expiry. iframes filtered.
  network_idle → Phase-2 fallback: sleep(idle_threshold_ms ?? 500).
                 FIXME(phase-3): swap in webRequest-based monitoring.

Injectable clock + sleep for fast deterministic tests.
Listeners cleaned up in finally on all paths.

12 wait.test cases.

Phase 2 Wave 2 — task B8.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要加 `webRequest` permission 到 manifest（涉及 host_permissions 风险，留 Phase 3）。
- 不要碰其他 handler 文件（B3/B4/B5/B6/B7 并行）。
- 不要用 real `setTimeout` 不让人测—— 用注入的 sleep + vitest fake timers。
