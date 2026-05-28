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
})
