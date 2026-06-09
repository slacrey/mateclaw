import { describe, expect, it } from 'vitest'
import { parseEdgeMessage, makeEdgeMessage, EdgeMessageKind } from './edge-protocol'

describe('edge-protocol', () => {
  it('serialises a ping', () => {
    const m = makeEdgeMessage({
      kind: EdgeMessageKind.Ping,
      sessionId: 'sess-1',
      traceId: 't1',
      payload: { echo: 'hi' },
    })
    expect(m.v).toBe(1)
    expect(m.kind).toBe('ping')
    expect(m.msg_id).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('session_id is always empty string when not provided (audit P0-1)', () => {
    const m = makeEdgeMessage({ kind: EdgeMessageKind.Ping, payload: { echo: 'x' } })
    expect(m.session_id).toBe('')
  })

  it('parses a valid envelope', () => {
    const raw = JSON.stringify({
      v: 1, msg_id: 'x', kind: 'pong', ts: 0, trace_id: 't',
      session_id: 's', payload: { echo: 'hi', server_ts: 1 },
    })
    const m = parseEdgeMessage(raw)
    expect(m).not.toBeNull()
    expect(m!.kind).toBe('pong')
  })

  it('rejects junk', () => {
    expect(parseEdgeMessage('not json')).toBeNull()
    expect(parseEdgeMessage('{}')).toBeNull()
  })

  it('treats unknown kinds as Unknown', () => {
    const raw = JSON.stringify({
      v: 1, msg_id: 'x', kind: 'future.thing', ts: 0, trace_id: 't',
      session_id: 's', payload: {},
    })
    const m = parseEdgeMessage(raw)
    expect(m).not.toBeNull()
    expect(m!.kind).toBe('__unknown__')
  })

  // ---------------------------------------------------------------
  // Protocol v1.1 — Phase 2 P1 wire kinds
  // Must match EdgeMessageKind (Java) + Kind (NH bridge) byte-for-byte.
  // ---------------------------------------------------------------

  it('v1.1 action.* kinds have exact wire strings', () => {
    expect(EdgeMessageKind.ActionExecute).toBe('action.execute')
    expect(EdgeMessageKind.ActionResult).toBe('action.result')
    expect(EdgeMessageKind.ActionCancel).toBe('action.cancel')
  })

  it('v1.1 indicator.* kinds have exact wire strings', () => {
    expect(EdgeMessageKind.IndicatorShow).toBe('indicator.show')
    expect(EdgeMessageKind.IndicatorHide).toBe('indicator.hide')
    expect(EdgeMessageKind.IndicatorCursor).toBe('indicator.cursor')
    expect(EdgeMessageKind.IndicatorToolUseHide).toBe('indicator.tool_use_hide')
    expect(EdgeMessageKind.IndicatorToolUseShow).toBe('indicator.tool_use_show')
    expect(EdgeMessageKind.IndicatorStopClicked).toBe('indicator.stop_clicked')
  })

  it('v1.1 a11y + event kinds have exact wire strings', () => {
    expect(EdgeMessageKind.A11ySnapshotRequest).toBe('a11y.snapshot.request')
    expect(EdgeMessageKind.A11ySnapshotResponse).toBe('a11y.snapshot.response')
    expect(EdgeMessageKind.EventPageNavigated).toBe('event.page.navigated')
    expect(EdgeMessageKind.EventTabClosed).toBe('event.tab.closed')
  })

  it('v1.2 screenshot.capture.request has exact wire string', () => {
    expect(EdgeMessageKind.ScreenshotCaptureRequest).toBe('screenshot.capture.request')
  })

  it('v1.2 screenshot.capture.response has exact wire string', () => {
    expect(EdgeMessageKind.ScreenshotCaptureResponse).toBe('screenshot.capture.response')
  })

  it('v2 browser runtime kinds have exact wire strings', () => {
    expect(EdgeMessageKind.BrowserActionRequest).toBe('browser.action.request')
    expect(EdgeMessageKind.BrowserActionResult).toBe('browser.action.result')
    expect(EdgeMessageKind.BrowserObservationCapture).toBe('browser.observation.capture')
    expect(EdgeMessageKind.BrowserObservationResult).toBe('browser.observation.result')
    expect(EdgeMessageKind.BrowserArtifactUploadChunk).toBe('browser.artifact.upload_chunk')
    expect(EdgeMessageKind.BrowserTelemetryBatch).toBe('browser.telemetry.batch')
    expect(EdgeMessageKind.BrowserHumanTakeover).toBe('browser.human.takeover')
  })

  it('v1.1 action.execute round-trips with tab_ref payload', () => {
    const m = makeEdgeMessage({
      kind: EdgeMessageKind.ActionExecute,
      payload: {
        tab_ref: 'main',
        kind: 'navigate',
        params: { url: 'https://example.com' },
        deadline_ms: 30000,
      },
    })
    const back = parseEdgeMessage(JSON.stringify(m))
    expect(back).not.toBeNull()
    expect(back!.kind).toBe(EdgeMessageKind.ActionExecute)
    expect((back!.payload as Record<string, unknown>)['tab_ref']).toBe('main')
  })

  it('v1.1 indicator.stop_clicked keeps session_id="" invariant (P0-1)', () => {
    // Ext → NH → CP direction. Extension MUST emit session_id="".
    const m = makeEdgeMessage({
      kind: EdgeMessageKind.IndicatorStopClicked,
      payload: { tab_ref: 42 },
    })
    expect(m.session_id).toBe('')
  })

  it('v1.1 a11y.snapshot.response round-trips', () => {
    const m = makeEdgeMessage({
      kind: EdgeMessageKind.A11ySnapshotResponse,
      payload: {
        snapshot_id: 'snap-1',
        captured_at_ms: 1730000000123,
        tab_ref: 42,
        tree: 'Button[ref=ref_1]: Submit',
        viewport: { w: 1280, h: 800 },
      },
    })
    const back = parseEdgeMessage(JSON.stringify(m))
    expect(back).not.toBeNull()
    expect(back!.kind).toBe(EdgeMessageKind.A11ySnapshotResponse)
  })

  it('v1.2 screenshot.capture.request round-trips', () => {
    const m = makeEdgeMessage({
      kind: EdgeMessageKind.ScreenshotCaptureRequest,
      payload: {
        tab_ref: 'main',
        format: 'png',
        quality: 90,
        scale_factor: 1,
      },
    })
    const back = parseEdgeMessage(JSON.stringify(m))
    expect(back).not.toBeNull()
    expect(back!.kind).toBe(EdgeMessageKind.ScreenshotCaptureRequest)
    expect((back!.payload as Record<string, unknown>)['tab_ref']).toBe('main')
  })

  it('v1.2 screenshot.capture.response round-trips with session_id="" invariant', () => {
    const m = makeEdgeMessage({
      kind: EdgeMessageKind.ScreenshotCaptureResponse,
      payload: {
        snapshot_id: 'shot-1',
        captured_at_ms: 1730000000123,
        tab_ref: 42,
        format: 'png',
        data_base64: 'iVBORw0KGgoAAAANS',
        viewport: { w: 1280, h: 800 },
        actual_dimensions: { w: 1280, h: 800 },
      },
    })
    const back = parseEdgeMessage(JSON.stringify(m))
    expect(back).not.toBeNull()
    expect(back!.kind).toBe(EdgeMessageKind.ScreenshotCaptureResponse)
    expect(back!.session_id).toBe('')
  })
})
