import { describe, expect, it, vi } from 'vitest'
import { ActionExecutor, type ActionHandlers } from './ActionExecutor'
import type { ActionRequest, ActionResult } from './types'

/**
 * Minimal Success builder for tests.
 */
function ok(payload: Record<string, unknown> = {}): ActionResult {
  return { ok: true, elapsed_ms: 0, payload }
}

function navigateReq(overrides: Partial<ActionRequest> = {}): ActionRequest {
  // Discriminated-union construction: assemble via `as unknown as` because
  // the spread breaks TS's kind→params narrowing even though the literal is
  // well-formed. Production code never builds requests this way — the wire
  // shape is parsed straight into the union with a real discriminator at
  // runtime; this is a test-only convenience.
  return {
    msg_id: 'm-nav',
    tab_ref: 'main',
    kind: 'navigate',
    params: { url: 'https://example.com', wait_for: 'load' },
    deadline_ms: 5000,
    ...overrides,
  } as unknown as ActionRequest
}

describe('ActionExecutor', () => {
  // ---------------------------------------------------------------
  // Happy-path dispatch — one test per ActionKind to prove the
  // switch covers all six kinds B3-B8 will need.
  // ---------------------------------------------------------------

  it('routes navigate kind to navigate handler with tabId + params', async () => {
    const navigate = vi.fn(async () => ok({ final_url: 'https://example.com', load_state: 'load' }))
    const exec = new ActionExecutor({ navigate } as unknown as ActionHandlers)

    const result = await exec.run(42, navigateReq())

    expect(navigate).toHaveBeenCalledExactlyOnceWith(42, expect.objectContaining({
      url: 'https://example.com',
      wait_for: 'load',
    }), expect.any(Number))
    expect(result.ok).toBe(true)
  })

  it('routes click kind to click handler', async () => {
    const click = vi.fn(async () => ok())
    const exec = new ActionExecutor({ click } as unknown as ActionHandlers)

    const result = await exec.run(42, {
      msg_id: 'm-click', tab_ref: 'main', kind: 'click',
      params: { x: 10, y: 20, button: 'left', click_count: 1 }, deadline_ms: 3000,
    })

    expect(click).toHaveBeenCalledOnce()
    expect(result.ok).toBe(true)
  })

  it('routes type / scroll / move_mouse / wait to their handlers', async () => {
    const type = vi.fn(async () => ok({ chars_typed: 5 }))
    const scroll = vi.fn(async () => ok())
    const move_mouse = vi.fn(async () => ok({ arrived_at_ms: 100, waypoints: 5 }))
    const wait = vi.fn(async () => ok({ waited_ms: 200 }))
    const exec = new ActionExecutor({ type, scroll, move_mouse, wait } as unknown as ActionHandlers)

    await exec.run(42, { msg_id: 'm', tab_ref: 'main', kind: 'type', params: { text: 'hello' }, deadline_ms: 1000 })
    await exec.run(42, { msg_id: 'm', tab_ref: 'main', kind: 'scroll', params: { direction: 'down', distance_px: 300, segments: 5 }, deadline_ms: 1000 })
    await exec.run(42, { msg_id: 'm', tab_ref: 'main', kind: 'move_mouse', params: { x: 50, y: 50, profile: 'natural' }, deadline_ms: 1000 })
    await exec.run(42, { msg_id: 'm', tab_ref: 'main', kind: 'wait', params: { strategy: 'network_idle' }, deadline_ms: 1000 })

    expect(type).toHaveBeenCalledOnce()
    expect(scroll).toHaveBeenCalledOnce()
    expect(move_mouse).toHaveBeenCalledOnce()
    expect(wait).toHaveBeenCalledOnce()
  })

  // ---------------------------------------------------------------
  // Failure paths
  // ---------------------------------------------------------------

  it('returns UNKNOWN_KIND failure when kind has no registered handler', async () => {
    const exec = new ActionExecutor({} as unknown as ActionHandlers)

    const result = await exec.run(42, {
      msg_id: 'm', tab_ref: 'main', kind: 'future-action', params: {}, deadline_ms: 1000,
    } as unknown as ActionRequest)

    expect(result.ok).toBe(false)
    if (result.ok === false) {
      expect(result.code).toBe('UNKNOWN_KIND')
      expect(result.retryable).toBe(false)
      expect(result.message).toContain('future-action')
    }
  })

  it('returns Failure with handler error message when handler throws', async () => {
    const navigate = vi.fn(async () => { throw new Error('CDP Page.enable timeout') })
    const exec = new ActionExecutor({ navigate } as unknown as ActionHandlers)

    const result = await exec.run(42, navigateReq())

    expect(result.ok).toBe(false)
    if (result.ok === false) {
      expect(result.message).toContain('CDP Page.enable timeout')
      expect(result.code).toBe('HANDLER_ERROR')
      expect(result.retryable).toBe(true)
    }
  })

  it('preserves typed error codes when handler throws an ActionFailureError', async () => {
    // Handlers signal typed wire errors (TIMEOUT_PAGE_LOAD, NO_TARGET_TAB,
    // DEVTOOLS_OPEN, etc.) by throwing ActionFailureError. The executor
    // unwraps these and uses the carried code/retryable, NOT 'HANDLER_ERROR'.
    const { ActionFailureError } = await import('./ActionExecutor')
    const navigate = vi.fn(async () => {
      throw new ActionFailureError('TIMEOUT_PAGE_LOAD', 'navigation timed out at 5s', true)
    })
    const exec = new ActionExecutor({ navigate } as unknown as ActionHandlers)

    const result = await exec.run(42, navigateReq())

    expect(result.ok).toBe(false)
    if (result.ok === false) {
      expect(result.code).toBe('TIMEOUT_PAGE_LOAD')
      expect(result.message).toContain('navigation timed out')
      expect(result.retryable).toBe(true)
    }
  })

  it('measures elapsed_ms on successful dispatch', async () => {
    // Wrap the handler so it returns ok() with elapsed_ms: 0 — the executor
    // overwrites it with the wall-clock elapsed from run() start to
    // handler return. Sleep 10ms to make the measurement non-trivial.
    const navigate = vi.fn(async () => {
      await new Promise(r => setTimeout(r, 10))
      return ok({ final_url: 'x' })
    })
    const exec = new ActionExecutor({ navigate } as unknown as ActionHandlers)

    const result = await exec.run(42, navigateReq())

    expect(result.ok).toBe(true)
    if (result.ok === true) {
      // Allow CI jitter — must be at least 5ms but under a generous 500ms ceiling.
      expect(result.elapsed_ms).toBeGreaterThanOrEqual(5)
      expect(result.elapsed_ms).toBeLessThan(500)
    }
  })

  it('passes deadline_ms through to the handler as third argument', async () => {
    // Handlers compute their own per-request timeout from this — the
    // executor doesn't enforce it itself (CP enforces deadline at the
    // ActionExecutionService layer). But we still pass it down so
    // handlers like navigate can race against chrome.webNavigation.
    const navigate = vi.fn(async () => ok({}))
    const exec = new ActionExecutor({ navigate } as unknown as ActionHandlers)

    await exec.run(42, navigateReq({ deadline_ms: 7777 }))

    expect(navigate).toHaveBeenCalledWith(42, expect.anything(), 7777)
  })
})
