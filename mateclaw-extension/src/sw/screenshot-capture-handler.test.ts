import { describe, expect, it, vi } from 'vitest'
import { ScreenshotCaptureHandler } from './screenshot-capture-handler'
import { EdgeMessageKind, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'

function fakeChrome(opts: {
  dataUrl?: string
  captureReject?: unknown
  tab?: Partial<chrome.tabs.Tab>
} = {}) {
  const captureVisibleTab = vi.fn(async () => {
    if (opts.captureReject) throw opts.captureReject
    return opts.dataUrl ?? 'data:image/png;base64,iVBORw0KGgoAAAANS'
  })
  const get = vi.fn(async () => ({
    id: 42,
    width: 1280,
    height: 800,
    ...opts.tab,
  }))

  return {
    tabs: {
      captureVisibleTab,
      get,
    },
  } as unknown as typeof globalThis.chrome
}

function fakeResolver(resolveImpl: (tabRef: TabRef) => Promise<number | null> = async () => 42) {
  return {
    resolve: vi.fn(resolveImpl),
  } as unknown as TabRefResolver
}

function screenshotRequest(
  payload: Record<string, unknown> = {},
  overrides: Partial<EdgeMessage> = {},
): EdgeMessage {
  return {
    v: 1,
    msg_id: 'request-1',
    kind: EdgeMessageKind.ScreenshotCaptureRequest,
    ts: 0,
    trace_id: 'trace-1',
    session_id: 'must-not-propagate',
    payload: {
      tab_ref: 'main',
      format: 'png',
      quality: 90,
      scale_factor: 1,
      ...payload,
    },
    ...overrides,
  }
}

function makeHandler(opts: {
  resolver?: TabRefResolver
  chrome?: typeof globalThis.chrome
  uuid?: () => string
  clock?: () => number
} = {}) {
  const sentUp: EdgeMessage[] = []
  const chrome = opts.chrome ?? fakeChrome()
  const resolver = opts.resolver ?? fakeResolver()
  const handler = new ScreenshotCaptureHandler({
    resolver,
    chrome,
    sendUp: msg => sentUp.push(msg),
    uuid: opts.uuid ?? (() => 'shot-1'),
    clock: opts.clock ?? (() => 1730000000123),
  })
  return { handler, sentUp, chrome, resolver }
}

describe('ScreenshotCaptureHandler', () => {
  it('happy path: returns base64 png plus viewport metadata', async () => {
    const { handler, sentUp, chrome } = makeHandler()

    await handler.handle(screenshotRequest())

    expect(chrome.tabs.captureVisibleTab).toHaveBeenCalledOnce()
    expect(chrome.tabs.captureVisibleTab).toHaveBeenCalledWith(-1, { format: 'png' })
    expect(chrome.tabs.get).toHaveBeenCalledExactlyOnceWith(42)
    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.kind).toBe(EdgeMessageKind.ScreenshotCaptureResponse)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'shot-1',
      captured_at_ms: 1730000000123,
      tab_ref: 42,
      format: 'png',
      data_base64: 'iVBORw0KGgoAAAANS',
      viewport: { w: 1280, h: 800 },
      actual_dimensions: { w: 1280, h: 800 },
    })
  })

  it('tab_ref cannot resolve -> NO_TARGET_TAB without capture', async () => {
    const resolver = fakeResolver(async () => null)
    const chrome = fakeChrome()
    const { handler, sentUp } = makeHandler({ resolver, chrome })

    await handler.handle(screenshotRequest({ tab_ref: 'active' }))

    expect(chrome.tabs.captureVisibleTab).not.toHaveBeenCalled()
    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'shot-1',
      captured_at_ms: 1730000000123,
      tab_ref: -1,
      error: {
        code: 'NO_TARGET_TAB',
      },
    })
  })

  it('malformed tab_ref -> NO_TARGET_TAB without resolver call', async () => {
    const resolver = fakeResolver()
    const chrome = fakeChrome()
    const { handler, sentUp } = makeHandler({ resolver, chrome })

    await handler.handle(screenshotRequest({ tab_ref: 'sidebar' }))

    expect(resolver.resolve).not.toHaveBeenCalled()
    expect(chrome.tabs.captureVisibleTab).not.toHaveBeenCalled()
    expect(sentUp[0]!.payload).toMatchObject({
      tab_ref: -1,
      error: { code: 'NO_TARGET_TAB' },
    })
  })

  it('captureVisibleTab rejection -> PERMISSION_DENIED', async () => {
    const chrome = fakeChrome({ captureReject: new Error('Failed to capture tab') })
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(screenshotRequest())

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'shot-1',
      captured_at_ms: 1730000000123,
      tab_ref: -1,
      error: {
        code: 'PERMISSION_DENIED',
        message: 'Failed to capture tab',
      },
    })
  })

  it('base64 above 500 KB -> SCREENSHOT_TOO_LARGE', async () => {
    const hugeBase64 = 'a'.repeat(500_001)
    const chrome = fakeChrome({ dataUrl: `data:image/png;base64,${hugeBase64}` })
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(screenshotRequest())

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'shot-1',
      captured_at_ms: 1730000000123,
      tab_ref: -1,
      error: {
        code: 'SCREENSHOT_TOO_LARGE',
      },
    })
    expect(String((sentUp[0]!.payload!.error as Record<string, unknown>).message)).toContain('500001')
  })

  it('in_reply_to and trace_id pass through from request', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(screenshotRequest({}, {
      msg_id: 'request-xyz',
      trace_id: 'trace-xyz',
    }))

    expect(sentUp[0]!.in_reply_to).toBe('request-xyz')
    expect(sentUp[0]!.trace_id).toBe('trace-xyz')
  })

  it('session_id="" on outbound (P0-1 invariant)', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(screenshotRequest())

    expect(sentUp[0]!.session_id).toBe('')
  })

  it('ignores non-screenshot messages', async () => {
    const chrome = fakeChrome()
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle({
      ...screenshotRequest(),
      kind: EdgeMessageKind.Ping,
    })

    expect(chrome.tabs.captureVisibleTab).not.toHaveBeenCalled()
    expect(sentUp).toHaveLength(0)
  })
})
