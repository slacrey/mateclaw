// NativeBridge wraps chrome.runtime.connectNative so the rest of the SW
// works in terms of EdgeMessages. Reconnect semantics are Phase 2 work.

import type { EdgeMessage } from '../shared/edge-protocol'
import { parseEdgeMessage } from '../shared/edge-protocol'

export class NativeBridge {
  private port: chrome.runtime.Port | null = null
  private readonly messageCbs = new Set<(m: EdgeMessage) => void>()
  private readonly disconnectCbs = new Set<() => void>()

  constructor(private readonly hostName: string) {}

  connect(): void {
    const port = chrome.runtime.connectNative(this.hostName)
    if (chrome.runtime.lastError) {
      throw new Error(`connectNative failed: ${chrome.runtime.lastError.message}`)
    }
    this.port = port

    port.onMessage.addListener((raw: unknown) => {
      const m =
        typeof raw === 'string'
          ? parseEdgeMessage(raw)
          : parseEdgeMessage(JSON.stringify(raw))
      if (!m) return
      this.messageCbs.forEach(cb => cb(m))
    })

    port.onDisconnect.addListener(() => {
      this.port = null
      this.disconnectCbs.forEach(cb => cb())
    })
  }

  send(m: EdgeMessage): void {
    if (!this.port) throw new Error('NativeBridge.send: not connected')
    this.port.postMessage(m)
  }

  /** Returns an unsubscribe function. */
  onMessage(cb: (m: EdgeMessage) => void): () => void {
    this.messageCbs.add(cb)
    return () => this.messageCbs.delete(cb)
  }

  onDisconnect(cb: () => void): void {
    this.disconnectCbs.add(cb)
  }

  disconnect(): void {
    this.port?.disconnect()
    this.port = null
  }
}
