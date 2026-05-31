import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ClickParams } from '../types'

export interface ClickHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number
  random?: () => number
  /** Extra micro-delay before mouseReleased (default: 30-100ms log-normal) */
  pressHoldMs?: () => number
  /**
   * Awaited AFTER the click lands so the click's DOM effect — a dropdown /
   * filter panel / menu opening — has time to render before the agent's next
   * observe reads the tree. Without this the click handler returns the instant
   * the mouse event is dispatched (~50ms), the agent observes immediately, and
   * observe's quiet-settle fires before the (async, React) panel paints — so the
   * panel is missed, the agent thinks the click failed and re-clicks, which
   * TOGGLES the panel shut (the "点开了又没了 / 好几次没获取到" flicker).
   * Default: an adaptive MutationObserver settle in the page via
   * chrome.scripting — waits for the post-click DOM burst to go quiet (bounded),
   * and returns fast when the click changed nothing. No-op (resolves at once)
   * when chrome.scripting is unavailable, e.g. unit tests.
   */
  settleAfterClick?: (tabId: number) => Promise<void>
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
  const settleAfterClick = deps.settleAfterClick ?? defaultSettleAfterClick

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

      // Let the click's DOM effect (a dropdown / filter panel / menu opening)
      // render before we return, so the agent's next observe captures it
      // instead of racing the async paint. Bounded + best-effort: a settle that
      // fails must NEVER fail a click that already landed.
      try {
        await settleAfterClick(tabId)
      } catch {
        // ignore — the click succeeded; settling is only a timing aid
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

/**
 * Default post-click settle: run an adaptive DOM-quiet wait IN THE PAGE via
 * chrome.scripting, so a panel/menu the click opened has rendered before the
 * agent observes. Resolves immediately when chrome.scripting is unavailable
 * (unit tests / non-SW contexts). Best-effort — swallows all errors.
 *
 * In-page timing:
 *  - GRACE (≤500ms): wait for the FIRST post-click mutation. If none arrives,
 *    the click changed nothing visible → resolve at GRACE (don't stall).
 *  - QUIET (180ms): once mutations start, resolve 180ms after they stop (the
 *    panel finished painting).
 *  - CAP (1200ms): hard ceiling regardless, so a perpetually-animating page
 *    can't hang the action.
 */
async function defaultSettleAfterClick(tabId: number): Promise<void> {
  const chromeApi = (globalThis as unknown as { chrome?: typeof chrome }).chrome
  if (!chromeApi?.scripting?.executeScript) return
  try {
    await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: () =>
        new Promise<void>(resolve => {
          const root = document.documentElement
          if (!root || typeof MutationObserver === 'undefined') {
            setTimeout(resolve, 250)
            return
          }
          const GRACE = 500
          const QUIET = 180
          const CAP = 1200
          let sawMutation = false
          let done = false
          let quietTimer: ReturnType<typeof setTimeout> | undefined
          const finish = () => {
            if (done) return
            done = true
            if (quietTimer !== undefined) clearTimeout(quietTimer)
            clearTimeout(capTimer)
            clearTimeout(graceTimer)
            obs.disconnect()
            resolve()
          }
          const obs = new MutationObserver(() => {
            sawMutation = true
            if (quietTimer !== undefined) clearTimeout(quietTimer)
            quietTimer = setTimeout(finish, QUIET)
          })
          obs.observe(root, { subtree: true, childList: true, attributes: true })
          const capTimer = setTimeout(finish, CAP)
          // No DOM change within GRACE ⇒ the click had no visible effect; stop.
          const graceTimer = setTimeout(() => {
            if (!sawMutation) finish()
          }, GRACE)
        }),
    })
  } catch {
    // best-effort — a failed settle must never fail the click
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
