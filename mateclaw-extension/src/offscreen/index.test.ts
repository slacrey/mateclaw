import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  OFFSCREEN_CONNECT,
  OFFSCREEN_DISCONNECT,
  OFFSCREEN_DISCONNECTED,
  OFFSCREEN_INBOUND,
  OFFSCREEN_SEND,
  OFFSCREEN_STATE,
} from '../shared/offscreen-protocol'

// Capture every DirectBridgeClient the offscreen host constructs so each test
// can drive its relay callbacks (onMessage/onStateChange/onDisconnect) and
// assert what the host posts back to the SW.
interface FakeBridge {
  deps: { deviceId: string; deviceName?: string; agentVersion: string }
  connected: boolean
  msgCbs: Array<(m: unknown) => void>
  stateCbs: Array<(s: string) => void>
  disconnectCbs: Array<() => void>
  connectArgs: { url: string; pat: string } | null
  sent: unknown[]
  disconnected: boolean
}
const bridges: FakeBridge[] = []

vi.mock('../sw/direct-bridge', () => {
  class FakeDirectBridgeClient {
    deps: FakeBridge['deps']
    connected = false
    msgCbs: Array<(m: unknown) => void> = []
    stateCbs: Array<(s: string) => void> = []
    disconnectCbs: Array<() => void> = []
    connectArgs: { url: string; pat: string } | null = null
    sent: unknown[] = []
    disconnected = false
    constructor(deps: FakeBridge['deps']) {
      this.deps = deps
      bridges.push(this as unknown as FakeBridge)
    }
    onMessage(cb: (m: unknown) => void) {
      this.msgCbs.push(cb)
      return () => {}
    }
    onStateChange(cb: (s: string) => void) {
      this.stateCbs.push(cb)
      return () => {}
    }
    onDisconnect(cb: () => void) {
      this.disconnectCbs.push(cb)
    }
    connect(url: string, pat: string) {
      this.connectArgs = { url, pat }
    }
    send(m: unknown) {
      this.sent.push(m)
    }
    disconnect() {
      this.disconnected = true
    }
  }
  return { DirectBridgeClient: FakeDirectBridgeClient }
})

let listeners: Array<(m: unknown) => void>
let sent: unknown[]

beforeEach(async () => {
  bridges.length = 0
  listeners = []
  sent = []
  const chrome = {
    runtime: {
      onMessage: {
        addListener: (cb: (m: unknown) => void) => listeners.push(cb),
        removeListener: () => {},
      },
      sendMessage: vi.fn(async (m: unknown) => {
        sent.push(m)
      }),
    },
  }
  vi.stubGlobal('chrome', chrome)
  vi.resetModules()
  await import('./index') // registers the onMessage listener at module load
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const CONNECT = {
  type: OFFSCREEN_CONNECT,
  serverUrl: 'wss://host/edge',
  pat: 'pat-1',
  deviceId: 'dev-1',
  deviceName: 'Box',
  agentVersion: '0.1.5',
}

function dispatch(m: unknown) {
  listeners.forEach(cb => cb(m))
}

describe('offscreen host', () => {
  it('CONNECT constructs the bridge with creds and connects', () => {
    dispatch(CONNECT)
    expect(bridges).toHaveLength(1)
    expect(bridges[0]!.deps).toMatchObject({
      deviceId: 'dev-1',
      deviceName: 'Box',
      agentVersion: '0.1.5',
    })
    expect(bridges[0]!.connectArgs).toEqual({ url: 'wss://host/edge', pat: 'pat-1' })
  })

  it('relays socket inbound frames up as OFFSCREEN_INBOUND', () => {
    dispatch(CONNECT)
    const b = bridges[0]!
    b.msgCbs.forEach(cb => cb({ kind: 'pong' }))
    expect(sent).toContainEqual({ type: OFFSCREEN_INBOUND, message: { kind: 'pong' } })
  })

  it('relays state changes up as OFFSCREEN_STATE with connected', () => {
    dispatch(CONNECT)
    const b = bridges[0]!
    b.connected = true
    b.stateCbs.forEach(cb => cb('open'))
    expect(sent).toContainEqual({ type: OFFSCREEN_STATE, state: 'open', connected: true })
  })

  it('relays socket close up as OFFSCREEN_DISCONNECTED', () => {
    dispatch(CONNECT)
    bridges[0]!.disconnectCbs.forEach(cb => cb())
    expect(sent).toContainEqual({ type: OFFSCREEN_DISCONNECTED })
  })

  it('OFFSCREEN_SEND forwards the frame to the socket', () => {
    dispatch(CONNECT)
    dispatch({ type: OFFSCREEN_SEND, message: { kind: 'heartbeat' } })
    expect(bridges[0]!.sent).toContainEqual({ kind: 'heartbeat' })
  })

  it('OFFSCREEN_DISCONNECT tears the socket down', () => {
    dispatch(CONNECT)
    dispatch({ type: OFFSCREEN_DISCONNECT })
    expect(bridges[0]!.disconnected).toBe(true)
  })

  it('a second CONNECT with different creds replaces the socket', () => {
    dispatch(CONNECT)
    dispatch({ ...CONNECT, serverUrl: 'wss://other/edge' })
    expect(bridges).toHaveLength(2)
    expect(bridges[0]!.disconnected).toBe(true)
  })

  it('a second CONNECT with same creds while connected is idempotent (re-announce only)', () => {
    dispatch(CONNECT)
    const b = bridges[0]!
    b.connected = true
    sent.length = 0
    dispatch(CONNECT) // identical creds — SW just woke and re-ran startup connect
    expect(bridges).toHaveLength(1) // no new socket
    expect(b.disconnected).toBe(false) // existing socket untouched
    expect(sent).toContainEqual({ type: OFFSCREEN_STATE, state: 'open', connected: true })
  })

  it('a second CONNECT with same creds but disconnected reconnects', () => {
    dispatch(CONNECT)
    bridges[0]!.connected = false
    dispatch(CONNECT) // same creds but socket not up → rebuild
    expect(bridges).toHaveLength(2)
    expect(bridges[0]!.disconnected).toBe(true)
  })

  it('ignores junk and our own outbound relay types', () => {
    dispatch(null)
    dispatch('nope')
    dispatch({ type: OFFSCREEN_INBOUND, message: { kind: 'pong' } })
    dispatch({ type: OFFSCREEN_STATE, state: 'open', connected: true })
    expect(bridges).toHaveLength(0)
  })
})
