import { describe, expect, it, vi } from 'vitest'
import { EdgeMessageKind, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import { VisualCoordinator } from './visual-coordinator'

function fakeResolver(tabId: number | null): TabRefResolver {
  return {
    resolve: vi.fn(async tabRef => (typeof tabRef === 'number' ? tabRef : tabId)),
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
    expect(sendMessage).toHaveBeenCalledExactlyOnceWith(123, {
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

  // -----------------------------------------------------------------
  // Phase 2.1 D3 — heartbeat publisher tests
  // -----------------------------------------------------------------

  describe('D3 heartbeat publisher', () => {
    type IntervalRecord = { cb: () => void; ms: number; handle: number }

    function makeCoordinatorWithFakeScheduler() {
      const intervals: IntervalRecord[] = []
      let nextHandle = 1
      const sendMessage = vi.fn(async () => undefined)
      const chrome = {
        tabs: { sendMessage },
      } as unknown as typeof globalThis.chrome
      const resolver = fakeResolver(42)
      const coordinator = new VisualCoordinator({
        resolver,
        chrome,
        heartbeatIntervalMs: 5000,
        scheduleInterval: (cb, ms) => {
          const handle = nextHandle++ as unknown as number
          intervals.push({ cb, ms, handle })
          return handle as unknown as ReturnType<typeof setInterval>
        },
        cancelInterval: handle => {
          const idx = intervals.findIndex(r => r.handle === (handle as unknown as number))
          if (idx >= 0) intervals.splice(idx, 1)
        },
      })
      return { coordinator, sendMessage, intervals }
    }

    it('indicator.show starts a per-tab heartbeat interval at the configured cadence', async () => {
      const { coordinator, intervals } = makeCoordinatorWithFakeScheduler()

      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))

      expect(intervals).toHaveLength(1)
      expect(intervals[0]!.ms).toBe(5000)
    })

    it('indicator.show is idempotent — repeated SHOW does not stack intervals', async () => {
      const { coordinator, intervals } = makeCoordinatorWithFakeScheduler()

      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))
      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))

      expect(intervals).toHaveLength(1)
    })

    it('indicator.hide stops the heartbeat interval', async () => {
      const { coordinator, intervals } = makeCoordinatorWithFakeScheduler()
      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))
      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorHide))

      expect(intervals).toHaveLength(0)
    })

    it('isShowingOn tracks per-tab show/hide state for the nav re-show hook', async () => {
      // The SW's webNavigation.onCompleted handler uses this to decide
      // whether to re-fire SHOW after a page reload — without this gate,
      // every navigation on every managed tab would force overlays even if
      // the agent isn't currently active.
      const { coordinator } = makeCoordinatorWithFakeScheduler()
      // fakeResolver always resolves to tabId=42; before any SHOW the
      // coordinator must report no overlay on it.
      expect(coordinator.isShowingOn(42)).toBe(false)

      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))
      expect(coordinator.isShowingOn(42)).toBe(true)
      // Some other tab id that never received SHOW must stay false.
      expect(coordinator.isShowingOn(7)).toBe(false)

      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorHide))
      expect(coordinator.isShowingOn(42)).toBe(false)
    })

    it('firing the interval callback emits INDICATOR_HEARTBEAT to the tab', async () => {
      const { coordinator, sendMessage, intervals } = makeCoordinatorWithFakeScheduler()
      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))

      // Clear the SHOW_AGENT_INDICATORS send and trigger one heartbeat tick.
      sendMessage.mockClear()
      await intervals[0]!.cb()
      // await any microtasks queued by the async heartbeat emit.
      await new Promise(resolve => setTimeout(resolve, 0))

      expect(sendMessage).toHaveBeenCalledExactlyOnceWith(42, { type: 'INDICATOR_HEARTBEAT' })
    })

    it('heartbeat send failure (tab gone) stops the interval for that tab', async () => {
      const { coordinator, sendMessage, intervals } = makeCoordinatorWithFakeScheduler()
      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))
      expect(intervals).toHaveLength(1)

      // Next heartbeat throws — tab was closed.
      sendMessage.mockRejectedValueOnce(new Error('No tab with id 42'))
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
      try {
        await intervals[0]!.cb()
        await new Promise(resolve => setTimeout(resolve, 0))
      } finally {
        warnSpy.mockRestore()
      }

      expect(intervals).toHaveLength(0)
    })

    it('multi-tab: distinct tabs each get their own heartbeat interval', async () => {
      const intervals: IntervalRecord[] = []
      let nextHandle = 1
      const sendMessage = vi.fn(async () => undefined)
      const chrome = { tabs: { sendMessage } } as unknown as typeof globalThis.chrome
      const resolver = {
        // Echo the int tab_ref verbatim so we can pass explicit 42 + 43.
        resolve: vi.fn(async tabRef => (typeof tabRef === 'number' ? tabRef : null)),
      } as unknown as TabRefResolver
      const coordinator = new VisualCoordinator({
        resolver,
        chrome,
        heartbeatIntervalMs: 5000,
        scheduleInterval: (cb, ms) => {
          const handle = nextHandle++ as unknown as number
          intervals.push({ cb, ms, handle })
          return handle as unknown as ReturnType<typeof setInterval>
        },
        cancelInterval: handle => {
          const idx = intervals.findIndex(r => r.handle === (handle as unknown as number))
          if (idx >= 0) intervals.splice(idx, 1)
        },
      })

      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow, { tab_ref: 42 }, 'm1'))
      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow, { tab_ref: 43 }, 'm2'))

      expect(intervals).toHaveLength(2)
    })

    it('stopAllHeartbeats clears every running interval', async () => {
      const { coordinator, intervals } = makeCoordinatorWithFakeScheduler()
      await coordinator.handle(indicatorEnvelope(EdgeMessageKind.IndicatorShow))
      expect(intervals).toHaveLength(1)

      coordinator.stopAllHeartbeats()
      expect(intervals).toHaveLength(0)
    })
  })
})
