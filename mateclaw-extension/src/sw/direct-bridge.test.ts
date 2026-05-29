import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { DirectBridgeClient, EDGE_SUBPROTOCOL } from './direct-bridge'
import { EdgeMessageKind, makeEdgeMessage } from '../shared/edge-protocol'

// Controllable mock WebSocket. Tests drive open/message/close manually and
// inspect what was sent. Tracks every instance so reconnect creates new ones.
class MockWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  readonly CONNECTING = 0
  readonly OPEN = 1
  readonly CLOSING = 2
  readonly CLOSED = 3

  static instances: MockWebSocket[] = []

  readyState = MockWebSocket.CONNECTING
  sent: string[] = []
  closeCalled = false

  onopen: ((ev?: unknown) => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onclose: ((ev?: unknown) => void) | null = null
  onerror: ((ev?: unknown) => void) | null = null

  constructor(
    readonly url: string,
    readonly protocols?: string | string[],
  ) {
    MockWebSocket.instances.push(this)
  }

  send(data: string): void {
    this.sent.push(data)
  }

  close(): void {
    this.closeCalled = true
    this.readyState = MockWebSocket.CLOSED
  }

  // --- test drivers ---
  fireOpen(): void {
    this.readyState = MockWebSocket.OPEN
    this.onopen?.()
  }

  fireMessage(obj: unknown): void {
    this.onmessage?.({ data: JSON.stringify(obj) })
  }

  fireClose(): void {
    this.readyState = MockWebSocket.CLOSED
    this.onclose?.()
  }

  sentMessages(): Array<Record<string, unknown>> {
    return this.sent.map(s => JSON.parse(s) as Record<string, unknown>)
  }

  static last(): MockWebSocket {
    const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1]
    if (!ws) throw new Error('no MockWebSocket instance')
    return ws
  }
}

const URL = 'ws://localhost:18088/api/v1/browser/edge'
const PAT = 'mc_secret_token'

function newClient() {
  return new DirectBridgeClient({
    deviceId: 'dev-123',
    deviceName: 'Test Device',
    agentVersion: '0.1.0',
    WebSocketImpl: MockWebSocket as unknown as typeof WebSocket,
  })
}

function helloAck(sessionId: string) {
  return {
    v: 1,
    msg_id: 'srv-1',
    kind: EdgeMessageKind.HelloAck,
    ts: 0,
    trace_id: 't',
    session_id: sessionId,
    payload: { session_id: sessionId },
  }
}

describe('DirectBridgeClient', () => {
  beforeEach(() => {
    MockWebSocket.instances = []
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('opens with the [mateclaw.edge.v1, bearer.<pat>] subprotocol array', () => {
    const c = newClient()
    c.connect(URL, PAT)
    const ws = MockWebSocket.last()
    expect(ws.url).toBe(URL)
    expect(ws.protocols).toEqual([EDGE_SUBPROTOCOL, `bearer.${PAT}`])
  })

  it('sends a HELLO with agent_version/device_id/device_name on open', () => {
    const c = newClient()
    c.connect(URL, PAT)
    MockWebSocket.last().fireOpen()

    const msgs = MockWebSocket.last().sentMessages()
    const hello = msgs.find(m => m['kind'] === EdgeMessageKind.Hello)
    expect(hello).toBeDefined()
    expect(hello!['session_id']).toBe('')
    const payload = hello!['payload'] as Record<string, unknown>
    expect(payload['agent_version']).toBe('0.1.0')
    expect(payload['device_id']).toBe('dev-123')
    expect(payload['device_name']).toBe('Test Device')
  })

  it('captures session_id from HELLO_ACK and stamps it on subsequent sends', () => {
    const c = newClient()
    c.connect(URL, PAT)
    const ws = MockWebSocket.last()
    ws.fireOpen()
    expect(c.connected).toBe(false) // no session yet

    ws.fireMessage(helloAck('SID-999'))
    expect(c.connected).toBe(true)

    // Outbound frame with empty session_id gets stamped.
    c.send(makeEdgeMessage({ kind: EdgeMessageKind.ActionResult }))
    const sent = ws.sentMessages()
    const actionResult = sent.find(m => m['kind'] === EdgeMessageKind.ActionResult)
    expect(actionResult).toBeDefined()
    expect(actionResult!['session_id']).toBe('SID-999')
  })

  it('forwards inbound parsed messages to onMessage subscribers', () => {
    const c = newClient()
    const cb = vi.fn()
    const unsub = c.onMessage(cb)
    c.connect(URL, PAT)
    const ws = MockWebSocket.last()
    ws.fireOpen()
    ws.fireMessage({
      v: 1,
      msg_id: 'x',
      kind: 'pong',
      ts: 0,
      trace_id: 't',
      session_id: '',
      payload: { echo: 'y' },
    })
    expect(cb).toHaveBeenCalled()
    expect(cb.mock.calls[0]?.[0]?.kind).toBe('pong')

    // unsubscribe stops further delivery
    unsub()
    cb.mockClear()
    ws.fireMessage({ v: 1, msg_id: 'x2', kind: 'pong', ts: 0, trace_id: 't', session_id: '' })
    expect(cb).not.toHaveBeenCalled()
  })

  it('send() throws when not OPEN', () => {
    const c = newClient()
    c.connect(URL, PAT)
    // socket is CONNECTING, not OPEN
    expect(() => c.send(makeEdgeMessage({ kind: EdgeMessageKind.Ping }))).toThrow(
      /not connected/,
    )
  })

  it('sends a Ping every 20s while OPEN', () => {
    vi.useFakeTimers()
    const c = newClient()
    c.connect(URL, PAT)
    const ws = MockWebSocket.last()
    ws.fireOpen()

    const before = ws.sentMessages().filter(m => m['kind'] === EdgeMessageKind.Ping).length
    vi.advanceTimersByTime(20_000)
    vi.advanceTimersByTime(20_000)
    const after = ws.sentMessages().filter(m => m['kind'] === EdgeMessageKind.Ping).length
    expect(after - before).toBe(2)
  })

  it('reconnects with exponential backoff after an unexpected close', () => {
    vi.useFakeTimers()
    const c = newClient()
    c.connect(URL, PAT)
    expect(MockWebSocket.instances.length).toBe(1)

    // Unexpected close → first reconnect after 1s.
    MockWebSocket.last().fireClose()
    expect(MockWebSocket.instances.length).toBe(1)
    vi.advanceTimersByTime(1_000)
    expect(MockWebSocket.instances.length).toBe(2)

    // Second unexpected close → next reconnect after 2s (backoff grows).
    MockWebSocket.last().fireClose()
    vi.advanceTimersByTime(1_000)
    expect(MockWebSocket.instances.length).toBe(2) // not yet
    vi.advanceTimersByTime(1_000)
    expect(MockWebSocket.instances.length).toBe(3)
  })

  it('resets backoff after a successful open', () => {
    vi.useFakeTimers()
    const c = newClient()
    c.connect(URL, PAT)

    // First close → reconnect after 1s, then it opens successfully.
    MockWebSocket.last().fireClose()
    vi.advanceTimersByTime(1_000)
    expect(MockWebSocket.instances.length).toBe(2)
    MockWebSocket.last().fireOpen() // resets attempt counter

    // Next close should again wait only 1s (not 2s).
    MockWebSocket.last().fireClose()
    vi.advanceTimersByTime(1_000)
    expect(MockWebSocket.instances.length).toBe(3)
  })

  it('intentional disconnect() suppresses reconnect', () => {
    vi.useFakeTimers()
    const c = newClient()
    c.connect(URL, PAT)
    const ws = MockWebSocket.last()
    ws.fireOpen()

    c.disconnect()
    expect(ws.closeCalled).toBe(true)

    // Advancing well past any backoff window creates no new socket.
    vi.advanceTimersByTime(60_000)
    expect(MockWebSocket.instances.length).toBe(1)
    expect(c.connected).toBe(false)
  })

  it('disconnect() stops the ping timer', () => {
    vi.useFakeTimers()
    const c = newClient()
    c.connect(URL, PAT)
    const ws = MockWebSocket.last()
    ws.fireOpen()
    c.disconnect()
    const pingsBefore = ws.sentMessages().filter(m => m['kind'] === EdgeMessageKind.Ping).length
    vi.advanceTimersByTime(60_000)
    const pingsAfter = ws.sentMessages().filter(m => m['kind'] === EdgeMessageKind.Ping).length
    expect(pingsAfter).toBe(pingsBefore)
  })

  it('emits connecting → open state transitions', () => {
    const c = newClient()
    const states: string[] = []
    c.onStateChange(s => states.push(s))
    c.connect(URL, PAT)
    MockWebSocket.last().fireOpen()
    expect(states).toContain('connecting')
    expect(states).toContain('open')
  })
})
