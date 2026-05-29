import { describe, expect, it, vi } from 'vitest'
import { SnapshotRequestHandler } from './snapshot-request-handler'
import { EdgeMessageKind, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'

function fakeChrome() {
  return {
    chrome: {
      scripting: {
        executeScript: vi.fn(async () => [{
          result: {
            tree: 'Button[ref=ref_1]: Submit',
            viewport: { w: 1280, h: 800 },
          },
          frameId: 0,
        }]),
      },
    } as unknown as typeof globalThis.chrome,
  }
}

function fakeResolver(resolveImpl: (tabRef: TabRef) => Promise<number | null> = async () => 42) {
  return {
    resolve: vi.fn(resolveImpl),
  } as unknown as TabRefResolver
}

function snapshotRequest(
  payload: Record<string, unknown> = {},
  overrides: Partial<EdgeMessage> = {},
): EdgeMessage {
  return {
    v: 1,
    msg_id: 'request-1',
    kind: EdgeMessageKind.A11ySnapshotRequest,
    ts: 0,
    trace_id: 'trace-1',
    session_id: 'must-not-propagate',
    payload: {
      tab_ref: 'main',
      filter: 'interactive',
      depth: 15,
      max_chars: 200000,
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
  const { chrome } = fakeChrome()
  const resolver = opts.resolver ?? fakeResolver()
  const handler = new SnapshotRequestHandler({
    resolver,
    chrome: opts.chrome ?? chrome,
    sendUp: msg => sentUp.push(msg),
    uuid: opts.uuid ?? (() => 'snap-1'),
    clock: opts.clock ?? (() => 1730000000123),
  })
  return { handler, sentUp, chrome: opts.chrome ?? chrome, resolver }
}

describe('SnapshotRequestHandler', () => {
  it('happy path: invokes scripting.executeScript with correct args + responds with tree+viewport', async () => {
    const { handler, sentUp, chrome } = makeHandler()

    await handler.handle(snapshotRequest())

    // Two calls now: (1) inject the idempotent a11y content script so the
    // extractor is guaranteed present, (2) run the extraction func.
    expect(chrome.scripting.executeScript).toHaveBeenCalledTimes(2)
    expect(chrome.scripting.executeScript).toHaveBeenNthCalledWith(1, expect.objectContaining({
      target: { tabId: 42, allFrames: false },
      files: ['content/a11y-tree.js'],
    }))
    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42, allFrames: false },
      args: ['interactive', 15, 200000, undefined, undefined],
    }))
    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.kind).toBe(EdgeMessageKind.A11ySnapshotResponse)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'snap-1',
      captured_at_ms: 1730000000123,
      tab_ref: 42,
      tree: 'Button[ref=ref_1]: Submit',
      viewport: { w: 1280, h: 800 },
    })
  })

  it('uses injected uuid factory for snapshot_id', async () => {
    const { handler, sentUp } = makeHandler({ uuid: () => 'snap-from-test' })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.snapshot_id).toBe('snap-from-test')
  })

  it('uses injected clock for captured_at_ms', async () => {
    const { handler, sentUp } = makeHandler({ clock: () => 999 })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.captured_at_ms).toBe(999)
  })

  it('tab_ref="main" resolves via TabRefResolver before invoking scripting', async () => {
    const resolver = fakeResolver(async tabRef => tabRef === 'main' ? 77 : null)
    const { handler, chrome } = makeHandler({ resolver })

    await handler.handle(snapshotRequest({ tab_ref: 'main' }))

    expect(resolver.resolve).toHaveBeenCalledExactlyOnceWith('main')
    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 77, allFrames: false },
    }))
  })

  it('tab_ref=42 (integer) resolves to 42 verbatim', async () => {
    const resolver = fakeResolver(async tabRef => typeof tabRef === 'number' ? tabRef : null)
    const { handler, chrome, sentUp } = makeHandler({ resolver })

    await handler.handle(snapshotRequest({ tab_ref: 42 }))

    expect(resolver.resolve).toHaveBeenCalledExactlyOnceWith(42)
    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42, allFrames: false },
    }))
    expect(sentUp[0]!.payload!.tab_ref).toBe(42)
  })

  it('unresolvable tab_ref -> response with error.code=NO_TARGET_TAB, no executeScript call', async () => {
    const resolver = fakeResolver(async () => null)
    const { handler, chrome, sentUp } = makeHandler({ resolver })

    await handler.handle(snapshotRequest({ tab_ref: 'active' }))

    expect(chrome.scripting.executeScript).not.toHaveBeenCalled()
    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'snap-1',
      captured_at_ms: 1730000000123,
      tab_ref: -1,
      tree: '',
      viewport: { w: 0, h: 0 },
      error: {
        code: 'NO_TARGET_TAB',
        retryable: false,
      },
    })
  })

  it('chrome.scripting.executeScript rejection -> response with error.code=SNAPSHOT_FAILED', async () => {
    const { chrome } = fakeChrome()
    vi.mocked(chrome.scripting.executeScript).mockRejectedValue(new Error('tab navigated'))
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(snapshotRequest())

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'snap-1',
      captured_at_ms: 1730000000123,
      tab_ref: 42,
      tree: '',
      viewport: { w: 0, h: 0 },
      error: {
        code: 'SNAPSHOT_FAILED',
        retryable: true,
      },
    })
    expect(String((sentUp[0]!.payload!.error as Record<string, unknown>).message)).toContain('tab navigated')
  })

  it('in_reply_to preserved from request msg_id', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(snapshotRequest({}, { msg_id: 'request-xyz' }))

    expect(sentUp[0]!.in_reply_to).toBe('request-xyz')
  })

  it('trace_id propagated from request', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(snapshotRequest({}, { trace_id: 'trace-xyz' }))

    expect(sentUp[0]!.trace_id).toBe('trace-xyz')
  })

  it('session_id="" on outbound (P0-1 invariant)', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.session_id).toBe('')
  })

  it('passes filter/depth/max_chars/ref_id as args to executeScript', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest({
      filter: 'interactive',
      depth: 10,
      max_chars: 50000,
      ref_id: 'ref_3',
    }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      args: ['interactive', 10, 50000, 'ref_3', undefined],
    }))
  })

  it('omits ref_id when not provided in request', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest({
      filter: 'interactive',
      depth: 10,
      max_chars: 50000,
    }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      args: ['interactive', 10, 50000, undefined, undefined],
    }))
  })

  it('uses target.frameIds when frame_id is explicitly provided', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest({ frame_id: 7 }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42, frameIds: [7] },
      args: ['interactive', 15, 200000, undefined, 7],
    }))
  })

  it('treats frame_id=0 as an explicit top-frame frameIds request', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest({ frame_id: 0 }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42, frameIds: [0] },
      args: ['interactive', 15, 200000, undefined, 0],
    }))
  })

  it('malformed frame_id -> response with SNAPSHOT_FAILED and no executeScript call', async () => {
    const { handler, chrome, sentUp } = makeHandler()

    await handler.handle(snapshotRequest({ frame_id: '7' }))

    expect(chrome.scripting.executeScript).not.toHaveBeenCalled()
    expect(sentUp[0]!.payload).toMatchObject({
      tree: '',
      viewport: { w: 0, h: 0 },
      error: {
        code: 'SNAPSHOT_FAILED',
        retryable: false,
      },
    })
  })

  it('tree string is passed through verbatim from injection result', async () => {
    const { chrome } = fakeChrome()
    ;(chrome.scripting.executeScript as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([({
      result: {
        tree: 'Button[ref=ref_1]: Submit\n  Link[ref=ref_2]: Learn more',
        viewport: { w: 1280, h: 800 },
      },
      frameId: 0,
    }) as unknown as chrome.scripting.InjectionResult<unknown>])
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.tree).toBe('Button[ref=ref_1]: Submit\n  Link[ref=ref_2]: Learn more')
  })

  it('viewport {w,h} from injection result is included in response', async () => {
    const { chrome } = fakeChrome()
    ;(chrome.scripting.executeScript as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([({
      result: {
        tree: 'Button[ref=ref_1]: Submit',
        viewport: { w: 390, h: 844 },
      },
      frameId: 0,
    }) as unknown as chrome.scripting.InjectionResult<unknown>])
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.viewport).toEqual({ w: 390, h: 844 })
  })
})
