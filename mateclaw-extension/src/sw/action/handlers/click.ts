import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ClickParams } from '../types'

export interface ClickHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number
  random?: () => number
  /** Extra micro-delay before mouseReleased (default: 30-100ms log-normal) */
  pressHoldMs?: () => number
}

type MouseButton = NonNullable<ClickParams['button']>

/**
 * click handler.
 *
 * Flow:
 *   1. debugger.attach(tabId) - idempotent.
 *   2. For each click in 1..click_count:
 *      a. send Input.dispatchMouseEvent { type: 'mousePressed', x, y, button, clickCount }
 *      b. wait pressHoldMs() (default: log-normal ~50ms - human finger delay)
 *      c. send Input.dispatchMouseEvent { type: 'mouseReleased', x, y, button, clickCount }
 *      d. if not last click, wait ~30-150ms between clicks (double/triple-click intervals)
 *   3. return Success.
 *
 * Throws ActionFailureError('SESSION_DETACHED') if DebuggerManager throws
 * SessionDetachedError (e.g. DevTools opened mid-click).
 */
export const clickHandler = (deps: ClickHandlerDeps): ActionHandler<ClickParams> => {
  const clock = deps.clock ?? Date.now
  const random = deps.random ?? Math.random
  const pressHoldMs = deps.pressHoldMs ?? (() => logNormalMs(55, 0.35, 30, 100, random))

  return async (tabId, params, _deadlineMs) => {
    const startedAt = clock()

    try {
      await deps.debugger.attach(tabId)

      const button = params.button ?? 'left'
      const clickCount = normalizeClickCount(params.click_count)

      for (let clickIndex = 1; clickIndex <= clickCount; clickIndex++) {
        await dispatchClickEvent(deps.debugger, tabId, 'mousePressed', params.x, params.y, button, clickIndex)
        await sleep(pressHoldMs())
        await dispatchClickEvent(deps.debugger, tabId, 'mouseReleased', params.x, params.y, button, clickIndex)

        if (clickIndex < clickCount) {
          await sleep(logNormalMs(80, 0.45, 30, 150, random))
        }
      }

      return {
        ok: true,
        elapsed_ms: Math.max(0, clock() - startedAt),
        payload: {},
      }
    } catch (err) {
      if (err instanceof SessionDetachedError) {
        throw new ActionFailureError('SESSION_DETACHED', err.message, true)
      }
      throw err
    }
  }
}

async function dispatchClickEvent(
  debug: DebuggerManager,
  tabId: number,
  type: 'mousePressed' | 'mouseReleased',
  x: number,
  y: number,
  button: MouseButton,
  clickCount: number,
): Promise<void> {
  await debug.send(tabId, 'Input.dispatchMouseEvent', {
    type,
    x,
    y,
    button,
    clickCount,
    modifiers: 0,
  })
}

function normalizeClickCount(value: number | undefined): number {
  if (value == null || !Number.isFinite(value)) return 1
  return Math.max(1, Math.floor(value))
}

function logNormalMs(
  medianMs: number,
  sigma: number,
  minMs: number,
  maxMs: number,
  random: () => number,
): number {
  const u1 = Math.max(random(), 1e-12)
  const u2 = random()
  const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
  const sample = Math.exp(Math.log(medianMs) + sigma * z)
  return clamp(Math.round(sample), minMs, maxMs)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function sleep(ms: number): Promise<void> {
  if (!Number.isFinite(ms) || ms <= 0) return Promise.resolve()
  return new Promise(resolve => setTimeout(resolve, ms))
}
