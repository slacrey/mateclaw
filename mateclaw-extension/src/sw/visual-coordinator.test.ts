import { describe, expect, it, vi } from 'vitest'
import { EdgeMessageKind, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import { VisualCoordinator } from './visual-coordinator'

function fakeResolver(tabId: number | null): TabRefResolver {
  return {
    resolve: vi.fn(async () => tabId),
  } as unknown as TabRefResolver
}

function fakeChrome(
  response: unknown = undefined,
): { chrome: typeof globalThis.chrome; sendMessage: ReturnType<typeof vi.fn> } {
  const sendMessage = vi.fn(async () => response)
  return {
    chrome: {
      tabs: { sendMessage },
    } as unknown as typeof globalThis.chrome,
    sendMessage,
  }
}

function indicatorEnvelope(
  kind: EdgeMessageKind,
  payload: Record<string, unknown> = { tab_ref: 'main' },
  msgId = 'm-indicator',
): EdgeMessage {
  return {
    v: 1,
    msg_id: msgId,
    kind,
    ts: 1_700_000_000_000,
    trace_id: 'trace-1',
    session_id: 'cp-session',
    payload,
  }
}

function makeCoordinator(opts: {
  tabId?: number | null
  chromeResponse?: unknown
} = {}) {
  const sentUp: EdgeMessage[] = []
  const resolver = fakeResolver('tabId' in opts ? opts.tabId ?? null : 42)
  const { chrome, sendMessage } = fakeChrome(opts.chromeResponse)
  const coordinator = new VisualCoordinator({
    resolver,
    chrome,
    sendUp: msg => sentUp.push(msg),
  })
  return { coordinator, resolver, sendMessage, sentUp }
}

describe('VisualCoordinator', () => {
  it('indicator.show with tab_ref="main" forwards SHOW_AGENT_INDICATORS to resolved tab', async () => {
    const { coordinator, resolver, sendMessage } = makeCoordinator()

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))

    expect(resolver.resolve).toHaveBeenCalledExactlyOnceWith('main')
    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, {
      type: 'SHOW_AGENT_INDICATORS',
      isMcp: undefined,
    })
  })

  it('indicator.show with isMcp=true preserves the flag in the forwarded message', async () => {
    const { coordinator, sendMessage } = makeCoordinator()

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow, {
      tab_ref: 'main',
      isMcp: true,
    }))

    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, {
      type: 'SHOW_AGENT_INDICATORS',
      isMcp: true,
    })
  })

  it('indicator.show maps wire is_mcp=true to the internal isMcp flag', async () => {
    const { coordinator, sendMessage } = makeCoordinator()

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow, {
      tab_ref: 'main',
      is_mcp: true,
    }))

    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, {
      type: 'SHOW_AGENT_INDICATORS',
      isMcp: true,
    })
  })

  it('indicator.hide forwards HIDE_AGENT_INDICATORS', async () => {
    const { coordinator, sendMessage } = makeCoordinator()

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorHide))

    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, {
      type: 'HIDE_AGENT_INDICATORS',
    })
  })

  it('indicator.cursor forwards INDICATOR_CURSOR with x,y', async () => {
    const { coordinator, sendMessage } = makeCoordinator({
      chromeResponse: { ok: true, arrived_at_ms: 123 },
    })

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorCursor, {
      tab_ref: 'main',
      x: 10,
      y: 20,
    }))

    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, {
      type: 'INDICATOR_CURSOR',
      x: 10,
      y: 20,
    })
  })

  it('indicator.tool_use_hide forwards TOOL_USE_HIDE', async () => {
    const { coordinator, sendMessage } = makeCoordinator()

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorToolUseHide))

    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, {
      type: 'TOOL_USE_HIDE',
    })
  })

  it('indicator.tool_use_show forwards TOOL_USE_SHOW', async () => {
    const { coordinator, sendMessage } = makeCoordinator()

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorToolUseShow))

    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, {
      type: 'TOOL_USE_SHOW',
    })
  })

  it('unresolvable tab_ref drops the envelope (no chrome.tabs.sendMessage call)', async () => {
    const { coordinator, sendMessage, sentUp } = makeCoordinator({ tabId: null })

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))

    expect(sendMessage).not.toHaveBeenCalled()
    expect(sentUp).toHaveLength(0)
  })

  it('integer tab_ref passes through verbatim', async () => {
    const { coordinator, resolver, sendMessage } = makeCoordinator()

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorHide, {
      tab_ref: 123,
    }))

    expect(resolver.resolve).toHaveBeenCalledExactlyOnceWith(123)
    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, {
      type: 'HIDE_AGENT_INDICATORS',
    })
  })

  it('indicator.cursor sendMessage response emits action.result upstream', async () => {
    const { coordinator, sentUp } = makeCoordinator({
      chromeResponse: { ok: true, arrived_at_ms: 123 },
    })
    const inbound = indicatorEnvelope(
      EdgeMessageKind.IndicatorCursor,
      { tab_ref: 'main', x: 10, y: 20 },
      'm-cursor',
    )

    await coordinator.handle(inbound)

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.kind).toBe(EdgeMessageKind.ActionResult)
    expect(sentUp[0]!.in_reply_to).toBe('m-cursor')
    expect(sentUp[0]!.trace_id).toBe('trace-1')
    expect(sentUp[0]!.payload).toMatchObject({
      ok: true,
      payload: { arrived_at_ms: 123 },
    })
    expect(typeof sentUp[0]!.payload?.elapsed_ms).toBe('number')
  })

  it('VisualCoordinator.handles() returns true for the 5 indicator.* kinds and false for action.execute / a11y.snapshot.request / etc.', () => {
    expect(VisualCoordinator.handles(EdgeMessageKind.IndicatorShow)).toBe(true)
    expect(VisualCoordinator.handles(EdgeMessageKind.IndicatorHide)).toBe(true)
    expect(VisualCoordinator.handles(EdgeMessageKind.IndicatorCursor)).toBe(true)
    expect(VisualCoordinator.handles(EdgeMessageKind.IndicatorToolUseHide)).toBe(true)
    expect(VisualCoordinator.handles(EdgeMessageKind.IndicatorToolUseShow)).toBe(true)

    expect(VisualCoordinator.handles(EdgeMessageKind.ActionExecute)).toBe(false)
    expect(VisualCoordinator.handles(EdgeMessageKind.A11ySnapshotRequest)).toBe(false)
    expect(VisualCoordinator.handles(EdgeMessageKind.IndicatorStopClicked)).toBe(false)
    expect(VisualCoordinator.handles(EdgeMessageKind.Unknown)).toBe(false)
  })

  it('chrome.tabs.sendMessage rejection (tab gone) is caught and logged, does not throw', async () => {
    const resolver = fakeResolver(42)
    const sendMessage = vi.fn(async () => {
      throw new Error('tab closed')
    })
    const coordinator = new VisualCoordinator({
      resolver,
      chrome: {
        tabs: { sendMessage },
      } as unknown as typeof globalThis.chrome,
      sendUp: vi.fn(),
    })
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    try {
      await expect(coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorHide))).resolves.toBeUndefined()
      expect(errSpy).toHaveBeenCalled()
    } finally {
      errSpy.mockRestore()
    }
  })

  it('session_id="" preserved on the action.result round-trip from indicator.cursor', async () => {
    const { coordinator, sentUp } = makeCoordinator({
      chromeResponse: { ok: true, arrived_at_ms: 123 },
    })

    await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorCursor, {
      tab_ref: 'main',
      x: 10,
      y: 20,
    }))

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.session_id).toBe('')
  })
})
