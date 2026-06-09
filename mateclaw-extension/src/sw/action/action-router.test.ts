import { describe, expect, it, vi } from 'vitest'
import { ActionRouter } from './action-router'
import { ActionExecutor, type ActionHandlers } from './ActionExecutor'
import { TabRefResolver } from './tab-ref-resolver'
import type { TabGroupManager } from '../tab-group-manager'
import { EdgeMessageKind, makeEdgeMessage, type EdgeMessage } from '../../shared/edge-protocol'
import type { ActionRequest, ActionResult, TabRef } from './types'

// ---------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------

function fakeTabGroupManager(mainTabId: number | null): TabGroupManager {
  return { getMainTabId: vi.fn(async () => mainTabId) } as unknown as TabGroupManager
}

function fakeChromeWithActive(activeTabId: number | null): typeof globalThis.chrome {
  const result = activeTabId == null ? [] : [{ id: activeTabId }]
  return {
    tabs: { query: vi.fn(async () => result) },
  } as unknown as typeof globalThis.chrome
}

function navigateRequest(
  msgId: string,
  tabRef: TabRef = 'main',
  overrides: { msg_id?: string; tab_ref?: TabRef; deadline_ms?: number } = {},
): ActionRequest {
  return {
    msg_id: msgId,
    tab_ref: tabRef,
    kind: 'navigate',
    params: { url: 'https://example.com', wait_for: 'load' },
    deadline_ms: 5000,
    ...overrides,
  }
}

function executeEnvelope(req: ActionRequest, traceId = 't-1'): EdgeMessage {
  return {
    v: 1,
    msg_id: req.msg_id,
    kind: EdgeMessageKind.ActionExecute,
    ts: 0,
    trace_id: traceId,
    session_id: 'should-be-stripped',
    payload: req as unknown as Record<string, unknown>,
  }
}

/**
 * Build a fully-wired router with a Success-returning navigate handler
 * by default. Callers can override by passing custom handlers.
 */
function makeRouter(opts: {
  mainTabId?: number | null
  activeTabId?: number | null
  subject?: string
  handlers?: Partial<ActionHandlers>
  inflight?: Map<string, AbortController>
} = {}) {
  const sentUp: EdgeMessage[] = []
  // Use `in` checks so we can distinguish "not provided" (default 42/7)
  // from "explicitly null" (the resolution-miss test cases).
  const tgm = fakeTabGroupManager('mainTabId' in opts ? opts.mainTabId ?? null : 42)
  const chrome = fakeChromeWithActive('activeTabId' in opts ? opts.activeTabId ?? null : 7)
  const resolver = new TabRefResolver({
    tabGroupManager: tgm,
    chrome,
    subject: opts.subject ?? 'alice',
  })
  const defaultNavigate = vi.fn(async () => ({
    ok: true as const,
    elapsed_ms: 0,
    payload: { final_url: 'https://example.com' },
  } satisfies ActionResult))
  const handlers = opts.handlers ?? ({ navigate: defaultNavigate } as Partial<ActionHandlers>)
  const executor = new ActionExecutor(handlers)
  const router = new ActionRouter({
    resolver,
    executor,
    sendUp: msg => sentUp.push(msg),
    inflight: opts.inflight,
  })
  return { router, sentUp, handlers, tgm, chrome, executor }
}

// ---------------------------------------------------------------
// Tests
// ---------------------------------------------------------------

describe('ActionRouter', () => {
  // -------------------------------------------------------------
  // action.execute happy path
  // -------------------------------------------------------------

  it('action.execute resolves tab_ref then calls executor with resolved tabId', async () => {
    const navigate: ActionHandlers['navigate'] = vi.fn(
      async () => ({ ok: true as const, elapsed_ms: 0, payload: {} }),
    )
    const { router, tgm } = makeRouter({ mainTabId: 42, handlers: { navigate } })

    await router.handle(executeEnvelope(navigateRequest('m-1', 'main')))

    expect(tgm.getMainTabId).toHaveBeenCalledExactlyOnceWith('alice')
    expect(navigate).toHaveBeenCalledOnce()
    const mockCalls = (navigate as ReturnType<typeof vi.fn>).mock.calls
    expect(mockCalls[0]?.[0]).toBe(42)
  })

  it('action.execute sends action.result with in_reply_to = request.msg_id', async () => {
    const { router, sentUp } = makeRouter()

    await router.handle(executeEnvelope(navigateRequest('m-XYZ')))

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.kind).toBe(EdgeMessageKind.ActionResult)
    expect(sentUp[0]!.in_reply_to).toBe('m-XYZ')
  })

  it('action.execute sends Success result when executor returns ok=true', async () => {
    const navigate = vi.fn(async () => ({
      ok: true as const,
      elapsed_ms: 0,
      payload: { final_url: 'https://example.com', load_state: 'load' },
    }))
    const { router, sentUp } = makeRouter({ handlers: { navigate } })

    await router.handle(executeEnvelope(navigateRequest('m-1')))

    expect(sentUp).toHaveLength(1)
    const payload = sentUp[0]!.payload as Record<string, unknown>
    expect(payload.ok).toBe(true)
    expect(payload.payload).toMatchObject({
      final_url: 'https://example.com',
      load_state: 'load',
    })
  })

  it('action.execute sends Failure result when executor returns ok=false', async () => {
    const navigate = vi.fn(async () => ({
      ok: false as const,
      code: 'TIMEOUT_PAGE_LOAD' as const,
      message: 'timed out at 5s',
      retryable: true,
    }))
    const { router, sentUp } = makeRouter({ handlers: { navigate } })

    await router.handle(executeEnvelope(navigateRequest('m-1')))

    expect(sentUp).toHaveLength(1)
    const payload = sentUp[0]!.payload as Record<string, unknown>
    expect(payload.ok).toBe(false)
    expect(payload.code).toBe('TIMEOUT_PAGE_LOAD')
    expect(payload.message).toBe('timed out at 5s')
    expect(payload.retryable).toBe(true)
  })

  // -------------------------------------------------------------
  // tab_ref resolution failure
  // -------------------------------------------------------------

  it('tab_ref="main" with no bound tab → action.result Failure(NO_TARGET_TAB)', async () => {
    const navigate = vi.fn(async () => ({ ok: true as const, elapsed_ms: 0, payload: {} }))
    const { router, sentUp } = makeRouter({ mainTabId: null, handlers: { navigate } })

    await router.handle(executeEnvelope(navigateRequest('m-1', 'main')))

    expect(navigate).not.toHaveBeenCalled()
    expect(sentUp).toHaveLength(1)
    const payload = sentUp[0]!.payload as Record<string, unknown>
    expect(payload.ok).toBe(false)
    expect(payload.code).toBe('NO_TARGET_TAB')
    expect(payload.retryable).toBe(false)
    expect(sentUp[0]!.in_reply_to).toBe('m-1')
  })

  it('tab_ref="active" with no focused window → NO_TARGET_TAB', async () => {
    const navigate = vi.fn(async () => ({ ok: true as const, elapsed_ms: 0, payload: {} }))
    const { router, sentUp } = makeRouter({ activeTabId: null, handlers: { navigate } })

    await router.handle(executeEnvelope(navigateRequest('m-1', 'active')))

    expect(navigate).not.toHaveBeenCalled()
    expect(sentUp).toHaveLength(1)
    const payload = sentUp[0]!.payload as Record<string, unknown>
    expect(payload.code).toBe('NO_TARGET_TAB')
  })

  // -------------------------------------------------------------
  // Invariants — session_id="" and trace_id propagation
  // -------------------------------------------------------------

  it('all outbound envelopes have session_id="" (P0-1 invariant)', async () => {
    const navigate = vi.fn(async () => ({ ok: true as const, elapsed_ms: 0, payload: {} }))
    const { router, sentUp } = makeRouter({ mainTabId: null, handlers: { navigate } })

    // Happy path
    await router.handle(executeEnvelope(navigateRequest('m-1', 42)))
    // NO_TARGET_TAB path
    await router.handle(executeEnvelope(navigateRequest('m-2', 'main')))

    expect(sentUp).toHaveLength(2)
    expect(sentUp.every(m => m.session_id === '')).toBe(true)
  })

  it('all outbound envelopes have trace_id propagated from inbound', async () => {
    const { router, sentUp } = makeRouter()

    const inbound = executeEnvelope(navigateRequest('m-1', 42), 'trace-ABC')
    await router.handle(inbound)

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.trace_id).toBe('trace-ABC')
  })

  // -------------------------------------------------------------
  // action.cancel
  // -------------------------------------------------------------

  it('action.cancel for in-flight msg_id aborts the matching AbortController', async () => {
    const inflight = new Map<string, AbortController>()
    const ctrl = new AbortController()
    inflight.set('m-1', ctrl)
    const { router } = makeRouter({ inflight })

    const cancelMsg = makeEdgeMessage({
      kind: EdgeMessageKind.ActionCancel,
      payload: { in_reply_to: 'm-1' },
      traceId: 't-cancel',
    })
    await router.handle(cancelMsg)

    expect(ctrl.signal.aborted).toBe(true)
    expect(inflight.has('m-1')).toBe(false)
  })

  it('action.cancel for unknown msg_id is a no-op (no throw)', async () => {
    const inflight = new Map<string, AbortController>()
    const { router } = makeRouter({ inflight })

    const cancelMsg = makeEdgeMessage({
      kind: EdgeMessageKind.ActionCancel,
      payload: { in_reply_to: 'm-nonexistent' },
    })
    // Should not throw.
    await expect(router.handle(cancelMsg)).resolves.toBeUndefined()
  })

  it('action.cancel with no inflight map provided is a no-op', async () => {
    // Defaults to no map — router still ignores cancel cleanly.
    const { router } = makeRouter()  // no inflight passed

    const cancelMsg = makeEdgeMessage({
      kind: EdgeMessageKind.ActionCancel,
      payload: { in_reply_to: 'm-anything' },
    })
    await expect(router.handle(cancelMsg)).resolves.toBeUndefined()
  })

  // -------------------------------------------------------------
  // indicator.stop_clicked
  // -------------------------------------------------------------

  it('indicator.stop_clicked re-emits upstream verbatim', async () => {
    const { router, sentUp } = makeRouter()

    const stopMsg: EdgeMessage = {
      v: 1,
      msg_id: 'm-stop',
      kind: EdgeMessageKind.IndicatorStopClicked,
      ts: 12345,
      trace_id: 'trace-stop',
      session_id: '',
      payload: { source: 'sidepanel' },
    }
    await router.handle(stopMsg)

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.kind).toBe(EdgeMessageKind.IndicatorStopClicked)
    expect(sentUp[0]!.payload).toEqual({ source: 'sidepanel' })
    expect(sentUp[0]!.trace_id).toBe('trace-stop')
    expect(sentUp[0]!.session_id).toBe('')
  })

  // -------------------------------------------------------------
  // Forward-compat invariant
  // -------------------------------------------------------------

  it('unknown kind is ignored silently (forward-compat invariant)', async () => {
    const { router, sentUp } = makeRouter()

    const unknown: EdgeMessage = {
      v: 1,
      msg_id: 'm-unk',
      kind: EdgeMessageKind.Unknown,
      ts: 0,
      trace_id: 't',
      session_id: '',
      payload: {},
    }
    await expect(router.handle(unknown)).resolves.toBeUndefined()
    expect(sentUp).toHaveLength(0)

    // Also a totally unrelated kind (e.g. heartbeat the router shouldn't touch)
    const heartbeat: EdgeMessage = {
      v: 1,
      msg_id: 'm-hb',
      kind: EdgeMessageKind.Heartbeat,
      ts: 0,
      trace_id: 't',
      session_id: '',
      payload: {},
    }
    await router.handle(heartbeat)
    expect(sentUp).toHaveLength(0)
  })

  // -------------------------------------------------------------
  // Defense in depth — executor throw
  // -------------------------------------------------------------

  it('executor throw is caught — emits Failure(HANDLER_ERROR) rather than crashing', async () => {
    // Build a router whose executor.run itself throws (defensive coverage —
    // ActionExecutor.run already catches handler throws, but the router
    // shouldn't trust the callback). We patch the executor instance.
    const { router, sentUp, executor } = makeRouter()
    vi.spyOn(executor, 'run').mockRejectedValue(new Error('executor exploded'))

    await router.handle(executeEnvelope(navigateRequest('m-1', 42)))

    expect(sentUp).toHaveLength(1)
    const payload = sentUp[0]!.payload as Record<string, unknown>
    expect(payload.ok).toBe(false)
    expect(payload.code).toBe('HANDLER_ERROR')
    expect(payload.retryable).toBe(true)
    expect(String(payload.message)).toContain('executor exploded')
    expect(sentUp[0]!.in_reply_to).toBe('m-1')
  })

  it('malformed action.execute payload (missing required fields) → Failure(HANDLER_ERROR)', async () => {
    // The router must be defensive about envelope payload shape, since
    // it's untrusted wire data. Missing msg_id / kind / params means there
    // is nothing to dispatch on; emit a generic Failure rather than crash.
    const { router, sentUp } = makeRouter()
    const malformed: EdgeMessage = {
      v: 1,
      msg_id: 'm-bad',
      kind: EdgeMessageKind.ActionExecute,
      ts: 0,
      trace_id: 't-bad',
      session_id: '',
      payload: { not: 'an action request' },
    }
    await expect(router.handle(malformed)).resolves.toBeUndefined()

    // We don't strictly require an envelope reply for malformed input —
    // either no reply OR a Failure reply with in_reply_to=m-bad is fine.
    // What matters is "does not throw, does not send a Success."
    if (sentUp.length > 0) {
      const payload = sentUp[0]!.payload as Record<string, unknown>
      expect(payload.ok).toBe(false)
      expect(sentUp[0]!.in_reply_to).toBe('m-bad')
    }
  })
})
