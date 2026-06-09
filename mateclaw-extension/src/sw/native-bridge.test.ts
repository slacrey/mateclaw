import { describe, expect, it, vi, beforeEach } from 'vitest'
import { NativeBridge } from './native-bridge'
import { EdgeMessageKind, makeEdgeMessage } from '../shared/edge-protocol'

describe('NativeBridge', () => {
  const port = {
    onMessage: { addListener: vi.fn(), removeListener: vi.fn() },
    onDisconnect: { addListener: vi.fn() },
    postMessage: vi.fn(),
    disconnect: vi.fn(),
  }

  beforeEach(() => {
    ;(globalThis as unknown as Record<string, unknown>).chrome = {
      runtime: {
        connectNative: vi.fn(() => port),
        lastError: undefined,
      },
    }
    vi.clearAllMocks()
    // Re-attach mocks after clearAllMocks so fn references survive
    port.onMessage.addListener = vi.fn()
    port.onMessage.removeListener = vi.fn()
    port.onDisconnect.addListener = vi.fn()
    port.postMessage = vi.fn()
    port.disconnect = vi.fn()
    ;(globalThis as unknown as Record<string, unknown>).chrome = {
      runtime: {
        connectNative: vi.fn(() => port),
        lastError: undefined,
      },
    }
  })

  it('connects to the named native host (com.mateclaw.browser_bridge)', () => {
    new NativeBridge('com.mateclaw.browser_bridge').connect()
    const chromeShim = (globalThis as unknown as {
      chrome: { runtime: { connectNative: ReturnType<typeof vi.fn> } }
    }).chrome
    expect(chromeShim.runtime.connectNative).toHaveBeenCalledWith('com.mateclaw.browser_bridge')
  })

  it('forwards messages via postMessage', () => {
    const b = new NativeBridge('com.mateclaw.browser_bridge')
    b.connect()
    const m = makeEdgeMessage({ kind: EdgeMessageKind.Ping, payload: { echo: 'x' } })
    b.send(m)
    expect(port.postMessage).toHaveBeenCalledWith(m)
  })

  it('delivers inbound messages to listeners', () => {
    const b = new NativeBridge('com.mateclaw.browser_bridge')
    b.connect()
    const cb = vi.fn()
    b.onMessage(cb)

    const inbound = port.onMessage.addListener.mock.calls[0]?.[0]
    expect(inbound).toBeDefined()
    inbound!({ v: 1, msg_id: 'x', kind: 'pong', ts: 0, trace_id: 't', session_id: 's', payload: {} })

    expect(cb).toHaveBeenCalled()
    expect(cb.mock.calls[0]?.[0]?.kind).toBe('pong')
  })

  it('disconnect() calls port.disconnect', () => {
    const b = new NativeBridge('com.mateclaw.browser_bridge')
    b.connect()
    b.disconnect()
    expect(port.disconnect).toHaveBeenCalled()
  })

  it('onDisconnect callback is registered', () => {
    const b = new NativeBridge('com.mateclaw.browser_bridge')
    b.connect()
    const cb = vi.fn()
    b.onDisconnect(cb)
    expect(port.onDisconnect.addListener).toHaveBeenCalled()
  })
})
