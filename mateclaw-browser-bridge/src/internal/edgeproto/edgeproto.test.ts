import { describe, it, expect } from 'vitest'
import { Kind, parse, make } from './edgeproto.js'

describe('edgeproto', () => {
  it('round-trips a message through JSON', () => {
    const msg = make({
      kind: Kind.Ping,
      msgId: '00000000-0000-0000-0000-000000000001',
      traceId: '00000000-0000-0000-0000-000000000002',
      sessionId: 'sess-abc',
      payload: { echo: 'hello' },
    })
    const raw = JSON.stringify(msg)
    const back = parse(raw)
    expect(back).not.toBeNull()
    expect(back!.kind).toBe(Kind.Ping)
    expect((back!.payload as Record<string, unknown>)['echo']).toBe('hello')
    expect(back!.session_id).toBe('sess-abc')
  })

  it('serialises known kinds to their wire strings', () => {
    const cases: [Kind, string][] = [
      [Kind.Hello, 'hello'],
      [Kind.HelloAck, 'hello.ack'],
      [Kind.Heartbeat, 'heartbeat'],
      [Kind.HeartbeatAck, 'heartbeat.ack'],
      [Kind.Ping, 'ping'],
      [Kind.Pong, 'pong'],
      [Kind.Error, 'error'],
    ]
    for (const [kind, wire] of cases) {
      const msg = make({ kind })
      const parsed = JSON.parse(JSON.stringify(msg))
      expect(parsed.kind).toBe(wire)
    }
  })

  it('decodes unknown kind to __unknown__', () => {
    const raw = JSON.stringify({
      v: 1,
      msg_id: 'x',
      kind: 'future.thing',
      ts: 0,
      trace_id: 'y',
      session_id: '',
    })
    const msg = parse(raw)
    expect(msg).not.toBeNull()
    expect(msg!.kind).toBe(Kind.Unknown)
  })

  it('returns null for invalid JSON', () => {
    expect(parse('not-json')).toBeNull()
  })

  // ---------------------------------------------------------------
  // Protocol v1.1 — Phase 2 P1 wire kinds
  // Mirrors EdgeMessageKind on the Java side (13 new kinds).
  // ---------------------------------------------------------------

  it('serialises v1.1 action kinds to exact wire strings', () => {
    const cases: [Kind, string][] = [
      [Kind.ActionExecute, 'action.execute'],
      [Kind.ActionResult, 'action.result'],
      [Kind.ActionCancel, 'action.cancel'],
    ]
    for (const [kind, wire] of cases) {
      const parsed = JSON.parse(JSON.stringify(make({ kind })))
      expect(parsed.kind).toBe(wire)
    }
  })

  it('serialises v1.1 indicator kinds to exact wire strings', () => {
    const cases: [Kind, string][] = [
      [Kind.IndicatorShow, 'indicator.show'],
      [Kind.IndicatorHide, 'indicator.hide'],
      [Kind.IndicatorCursor, 'indicator.cursor'],
      [Kind.IndicatorToolUseHide, 'indicator.tool_use_hide'],
      [Kind.IndicatorToolUseShow, 'indicator.tool_use_show'],
      [Kind.IndicatorStopClicked, 'indicator.stop_clicked'],
    ]
    for (const [kind, wire] of cases) {
      const parsed = JSON.parse(JSON.stringify(make({ kind })))
      expect(parsed.kind).toBe(wire)
    }
  })

  it('serialises v1.1 a11y + event kinds to exact wire strings', () => {
    const cases: [Kind, string][] = [
      [Kind.A11ySnapshotRequest, 'a11y.snapshot.request'],
      [Kind.A11ySnapshotResponse, 'a11y.snapshot.response'],
      [Kind.EventPageNavigated, 'event.page.navigated'],
      [Kind.EventTabClosed, 'event.tab.closed'],
    ]
    for (const [kind, wire] of cases) {
      const parsed = JSON.parse(JSON.stringify(make({ kind })))
      expect(parsed.kind).toBe(wire)
    }
  })

  it('round-trips action.execute envelope through parse()', () => {
    const msg = make({
      kind: Kind.ActionExecute,
      payload: {
        tab_ref: 'main',
        kind: 'navigate',
        params: { url: 'https://example.com' },
        deadline_ms: 30000,
      },
    })
    const back = parse(JSON.stringify(msg))
    expect(back).not.toBeNull()
    expect(back!.kind).toBe(Kind.ActionExecute)
    expect((back!.payload as Record<string, unknown>)['tab_ref']).toBe('main')
  })

  it('round-trips a11y.snapshot.response envelope through parse()', () => {
    const msg = make({
      kind: Kind.A11ySnapshotResponse,
      payload: {
        snapshot_id: 'snap-1',
        captured_at_ms: 1730000000123,
        tab_ref: 42,
        tree: 'Button[ref=ref_1]: Submit',
        viewport: { w: 1280, h: 800 },
      },
    })
    const back = parse(JSON.stringify(msg))
    expect(back).not.toBeNull()
    expect(back!.kind).toBe(Kind.A11ySnapshotResponse)
  })

  it('round-trips event.tab.closed envelope through parse()', () => {
    const msg = make({
      kind: Kind.EventTabClosed,
      payload: { tab_ref: 42 },
    })
    const back = parse(JSON.stringify(msg))
    expect(back).not.toBeNull()
    expect(back!.kind).toBe(Kind.EventTabClosed)
  })
})
