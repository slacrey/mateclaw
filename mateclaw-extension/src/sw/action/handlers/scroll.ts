import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ScrollParams } from '../types'
import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'

export interface ScrollHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number
  random?: () => number
  /** Per-segment delay (default: log-normal ~60-180ms - wheel push cadence) */
  segmentIntervalMs?: () => number
  /** Returns the viewport center for the current tab (defaults to a static
   *  midpoint; real impl can use chrome.tabs.get + chrome.action.getZoom, but
   *  the executor doesn't have to be pixel-perfect - the page coords don't
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
 *        up    -> deltaY = -d
 *        down  -> deltaY = +d
 *        left  -> deltaX = -d
 *        right -> deltaX = +d
 *   5. For i in 0..segments-1:
 *      a. send Input.dispatchMouseWheelEvent { type:'mouseWheel', x, y, deltaX, deltaY }
 *      b. if i < segments-1, wait segmentIntervalMs()
 *   6. return Success.
 *
 * Throws ActionFailureError('SESSION_DETACHED') on detach.
 */
export const scrollHandler = (deps: ScrollHandlerDeps): ActionHandler<ScrollParams> => {
  const clock = deps.clock ?? Date.now
  const random = deps.random ?? Math.random
  const segmentIntervalMs = deps.segmentIntervalMs ?? (() => defaultSegmentIntervalMs(random))
  const viewportCenter = deps.viewportCenter ?? (async () => ({ x: 640, y: 400 }))

  return async (tabId, params, _deadlineMs) => {
    const startedAt = clock()

    try {
      await deps.debugger.attach(tabId)

      const { x, y } = await viewportCenter(tabId)
      const segmentCount = normalizeSegments(params.segments)
      const distances = integerSegments(params.distance_px, segmentCount)

      for (let i = 0; i < distances.length; i += 1) {
        const distance = signedDistance(params.direction, distances[i] ?? 0)
        const deltaX = params.direction === 'left' || params.direction === 'right' ? distance : 0
        const deltaY = params.direction === 'up' || params.direction === 'down' ? distance : 0

        await deps.debugger.send(tabId, 'Input.dispatchMouseWheelEvent', {
          type: 'mouseWheel',
          x,
          y,
          deltaX,
          deltaY,
          modifiers: 0,
        })

        if (i < distances.length - 1) {
          await sleep(segmentIntervalMs())
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

function normalizeSegments(segments: number | undefined): number {
  return Math.max(1, Math.floor(segments ?? 5))
}

function integerSegments(distancePx: number, segments: number): number[] {
  const total = Math.max(0, Math.round(Math.abs(distancePx)))
  const base = Math.floor(total / segments)
  const remainder = total % segments

  return Array.from({ length: segments }, (_, i) => base + (i < remainder ? 1 : 0))
}

function signedDistance(direction: ScrollParams['direction'], distance: number): number {
  if (direction === 'up' || direction === 'left') return -distance
  return distance
}

function defaultSegmentIntervalMs(random: () => number): number {
  const u1 = clampUnit(random())
  const u2 = clampUnit(random())
  const normal = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
  const median = Math.log(105)
  const sigma = 0.28
  return Math.round(clamp(Math.exp(median + sigma * normal), 60, 180))
}

function clampUnit(value: number): number {
  return clamp(value, Number.EPSILON, 1 - Number.EPSILON)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

async function sleep(ms: number): Promise<void> {
  await new Promise<void>(resolve => setTimeout(resolve, Math.max(0, ms)))
}
