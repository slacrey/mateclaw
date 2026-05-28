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
})
