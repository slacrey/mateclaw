import type { MoveMouseParams } from '../types'
import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DebuggerManager } from '../../debugger-manager'
import { SessionDetachedError } from '../../debugger-manager'
import { generate, type Point } from '../../../lib/windmouse'

/**
 * Default async sleeper. Resolves after `ms` real milliseconds.
 *
 * Tests inject their own stub so they can assert on the sleep durations
 * without sitting through real timing — see `move_mouse.test.ts`.
 */
function defaultSleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms))
}

export interface MoveMouseHandlerDeps {
  /**
   * The DebuggerManager owning the CDP session for this tab. The handler
   * calls `attach(tabId)` before any send (idempotent in B1) and then
   * dispatches every waypoint via `send(tabId, 'Input.dispatchMouseEvent', …)`.
   */
  debugger: DebuggerManager
  /** Injectable RNG passed through to WindMouse. Defaults to Math.random. */
  random?: () => number
  /** Injectable clock — defaults to Date.now (for elapsed bookkeeping). */
  clock?: () => number
  /** Async sleeper between waypoints — defaults to setTimeout-based. Tests stub this. */
  sleep?: (ms: number) => Promise<void>
  /**
   * Tracks the current cursor position per tab so consecutive moves
   * continue from where the previous one ended. Persisted in-memory only
   * (the SW global). Phase 2 keeps this in the handler closure; later
   * phases may promote it onto a session-scoped state object.
   */
  cursorState?: Map<number, Point>
}

/**
 * `move_mouse` action handler — Phase 2 task B7.
 *
 * Consumes the WindMouse library (B9) to generate an organic, jittered
 * path between the current cursor position and the requested target,
 * then dispatches each interior waypoint as a CDP `Input.dispatchMouseEvent`
 * of type `mouseMoved`. Inter-waypoint timing follows the deltas WindMouse
 * embeds in each `Waypoint.t` (ms since path start), so the cursor's
 * apparent velocity follows the same log-normal-jittered curve as the
 * spatial path.
 *
 * Flow:
 *   1. `debugger.attach(tabId)` (idempotent — safe to call on every action).
 *   2. Resolve `from` from `cursorState`; default `{0, 0}` on the first
 *      move per tab.
 *   3. Generate waypoints via `windmouse.generate(from, to, …)`.
 *   4. For each waypoint **after the first** (the cursor is already at
 *      `from`, so dispatching it would be redundant):
 *        a. `sleep(waypoint.t - prevWaypoint.t)` ms.
 *        b. `debugger.send(tabId, 'Input.dispatchMouseEvent',
 *                          { type: 'mouseMoved', x, y, button: 'none' })`.
 *   5. Update `cursorState[tabId] = { x: params.x, y: params.y }`.
 *   6. Return Success — payload carries `waypoints` (total count, including
 *      the skipped starting point) and `arrived_at_ms` (clock at completion).
 *
 * Errors:
 *   - `SessionDetachedError` from `debugger.send` (the tab was closed, the
 *     user opened DevTools, etc.) is rethrown as
 *     `ActionFailureError('SESSION_DETACHED', …, retryable=false)`. The
 *     ActionExecutor unwraps the typed code onto the wire.
 *   - Anything else propagates and lands as `HANDLER_ERROR` upstream.
 *
 * Deadlines: this handler does **not** race against `deadlineMs`. WindMouse
 * paths are bounded (≤ 800ms by default) and CDP sends complete in single
 * digits of ms, so the worst case is well within typical deadlines. The
 * Control Plane still enforces the deadline at `ActionExecutionService` —
 * a timeout there triggers `action.cancel` which detaches and bubbles
 * `SESSION_DETACHED` up through us naturally.
 */
export const moveMouseHandler = (deps: MoveMouseHandlerDeps): ActionHandler<MoveMouseParams> => {
  const random = deps.random ?? Math.random
  const clock = deps.clock ?? Date.now
  const sleep = deps.sleep ?? defaultSleep
  const cursorState = deps.cursorState ?? new Map<number, Point>()

  return async (tabId, params, _deadlineMs) => {
    await deps.debugger.attach(tabId)

    const from = cursorState.get(tabId) ?? { x: 0, y: 0 }
    const to: Point = { x: params.x, y: params.y }

    const waypoints = generate(from, to, {
      profile: params.profile ?? 'natural',
      random,
    })

    // Skip the very first waypoint — it equals `from` and the cursor is
    // already there. Walking from index 1 also lets us read `t` deltas
    // cleanly: each dispatch is preceded by a sleep equal to (current
    // waypoint's t) - (previous waypoint's t).
    for (let i = 1; i < waypoints.length; i++) {
      const prev = waypoints[i - 1]!
      const wp = waypoints[i]!
      const gap = Math.max(0, wp.t - prev.t)

      await sleep(gap)

      try {
        await deps.debugger.send(tabId, 'Input.dispatchMouseEvent', {
          type: 'mouseMoved',
          x: wp.x,
          y: wp.y,
          button: 'none',
        })
      } catch (err) {
        if (err instanceof SessionDetachedError) {
          throw new ActionFailureError(
            'SESSION_DETACHED',
            err.message,
            false,
          )
        }
        throw err
      }
    }

    cursorState.set(tabId, { x: params.x, y: params.y })

    return {
      ok: true,
      elapsed_ms: 0, // ActionExecutor overwrites with wall-clock measurement
      payload: {
        arrived_at_ms: clock(),
        waypoints: waypoints.length,
      },
    }
  }
}
