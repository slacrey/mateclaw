// DirectBridgeClient — Phase 3.1 direct-WSS transport.
//
// Exposes the SAME public surface as NativeBridge (connect/send/onMessage/
// onDisconnect/disconnect, where onMessage returns an unsubscribe fn) so the
// service worker is transport-agnostic. Adds onStateChange + a `connected`
// getter for the sidepanel status pill and the `ping` external handler.
//
// Auth + handshake (see docs/specs/phase-3.1-contract.md §1):
//   - opens `new WebSocket(url, ['mateclaw.edge.v1', `bearer.${pat}`])`; the
//     PAT rides in Sec-WebSocket-Protocol, the server echoes `mateclaw.edge.v1`.
//   - on open → sends HELLO (session_id:"") with {agent_version, device_id,
//     device_name}.
//   - on HELLO_ACK → captures payload.session_id and STAMPS it on every
//     subsequent outbound frame (the job the Native Host did in NH mode).
//   - Ping every 20s while OPEN; exponential backoff reconnect on unexpected
//     close (1s→2s→…→30s cap) using the last serverUrl/pat, suppressed after an
//     intentional disconnect().

import type { EdgeMessage } from '../shared/edge-protocol'
import {
  EdgeMessageKind,
  makeEdgeMessage,
  parseEdgeMessage,
} from '../shared/edge-protocol'

export type BridgeState = 'connecting' | 'open' | 'closed'

/** Subprotocol the server must echo on the 101 response. */
export const EDGE_SUBPROTOCOL = 'mateclaw.edge.v1'

const PING_INTERVAL_MS = 20_000
const BACKOFF_BASE_MS = 1_000
const BACKOFF_CAP_MS = 30_000

export interface DirectBridgeDeps {
  /** Stable per-install device id (ConfigStore.getDeviceId). */
  deviceId: string
  /** Human label for this device, if known. */
  deviceName?: string
  /** Extension version for the HELLO payload (manifest version). */
  agentVersion: string
  /** Injectable WebSocket ctor for tests; defaults to the global. */
  WebSocketImpl?: typeof WebSocket
}

export class DirectBridgeClient {
  private ws: WebSocket | null = null
  private serverUrl: string | null = null
  private pat: string | null = null

  /** Server-issued session id captured from HELLO_ACK; "" until then. */
  private sessionId = ''

  private pingTimer: ReturnType<typeof setInterval> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempt = 0
  /** Set by disconnect(); suppresses the auto-reconnect on the next close. */
  private intentionalClose = false

  private readonly messageCbs = new Set<(m: EdgeMessage) => void>()
  private readonly disconnectCbs = new Set<() => void>()
  private readonly stateCbs = new Set<(s: BridgeState) => void>()

  private readonly WebSocketImpl: typeof WebSocket
  private readonly deviceId: string
  private readonly deviceName?: string
  private readonly agentVersion: string

  constructor(deps: DirectBridgeDeps) {
    this.deviceId = deps.deviceId
    this.deviceName = deps.deviceName
    this.agentVersion = deps.agentVersion
    this.WebSocketImpl = deps.WebSocketImpl ?? WebSocket
  }

  /** True once the socket is OPEN and we have a server-issued session_id. */
  get connected(): boolean {
    return this.ws?.readyState === this.WebSocketImpl.OPEN && this.sessionId !== ''
  }

  /**
   * Connect (or replace the current connection) to `serverUrl` authenticating
   * with `pat`. Stores both for reconnect. Re-connecting tears down any prior
   * socket first.
   */
  connect(serverUrl: string, pat: string): void {
    // A fresh connect supersedes any pending reconnect/timers + old socket.
    this.clearTimers()
    this.intentionalClose = false
    if (this.ws) {
      // Detach our listeners before closing so the close doesn't trigger a
      // reconnect for the superseded socket.
      this.teardownSocket(this.ws)
      try {
        this.ws.close()
      } catch {
        // ignore
      }
    }
    this.serverUrl = serverUrl
    this.pat = pat
    this.openSocket()
  }

  private openSocket(): void {
    if (this.serverUrl === null || this.pat === null) return
    this.sessionId = ''
    this.emitState('connecting')

    const ws = new this.WebSocketImpl(this.serverUrl, [
      EDGE_SUBPROTOCOL,
      `bearer.${this.pat}`,
    ])
    this.ws = ws

    ws.onopen = () => {
      this.reconnectAttempt = 0
      this.emitState('open')
      this.startPing()
      // HELLO: session_id stays "" per protocol; server replies HELLO_ACK.
      this.rawSend(
        makeEdgeMessage({
          kind: EdgeMessageKind.Hello,
          payload: {
            agent_version: this.agentVersion,
            device_id: this.deviceId,
            device_name: this.deviceName ?? null,
          },
        }),
      )
    }

    ws.onmessage = (ev: MessageEvent) => {
      const raw = typeof ev.data === 'string' ? ev.data : String(ev.data)
      const m = parseEdgeMessage(raw)
      if (!m) return
      if (m.kind === EdgeMessageKind.HelloAck) {
        const sid = m.payload?.['session_id']
        if (typeof sid === 'string' && sid.length > 0) {
          this.sessionId = sid
        }
      }
      this.messageCbs.forEach(cb => cb(m))
    }

    ws.onclose = () => {
      this.handleClose()
    }

    ws.onerror = () => {
      // onerror is always followed by onclose; let handleClose drive reconnect.
    }
  }

  private handleClose(): void {
    this.stopPing()
    this.ws = null
    this.sessionId = ''
    this.emitState('closed')
    this.disconnectCbs.forEach(cb => cb())
    if (!this.intentionalClose) {
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect(): void {
    if (this.serverUrl === null || this.pat === null) return
    const delay = Math.min(
      BACKOFF_BASE_MS * 2 ** this.reconnectAttempt,
      BACKOFF_CAP_MS,
    )
    this.reconnectAttempt += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.openSocket()
    }, delay)
  }

  private startPing(): void {
    this.stopPing()
    this.pingTimer = setInterval(() => {
      if (this.ws?.readyState === this.WebSocketImpl.OPEN) {
        this.rawSend(makeEdgeMessage({ kind: EdgeMessageKind.Ping }))
      }
    }, PING_INTERVAL_MS)
  }

  private stopPing(): void {
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer)
      this.pingTimer = null
    }
  }

  private clearTimers(): void {
    this.stopPing()
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.reconnectAttempt = 0
  }

  private teardownSocket(ws: WebSocket): void {
    ws.onopen = null
    ws.onmessage = null
    ws.onclose = null
    ws.onerror = null
  }

  /**
   * Send an EdgeMessage. If we hold a captured session_id and the message's
   * session_id is "", stamp it before serialising — this is the NH bridge's old
   * role, now owned by the SW in direct mode.
   */
  send(m: EdgeMessage): void {
    if (!this.ws || this.ws.readyState !== this.WebSocketImpl.OPEN) {
      throw new Error('DirectBridgeClient.send: not connected')
    }
    this.rawSend(m)
  }

  /** Stamp + serialise without the OPEN guard (used for HELLO/Ping internally). */
  private rawSend(m: EdgeMessage): void {
    const stamped =
      this.sessionId !== '' && m.session_id === ''
        ? { ...m, session_id: this.sessionId }
        : m
    this.ws?.send(JSON.stringify(stamped))
  }

  /** Returns an unsubscribe function (matches NativeBridge). */
  onMessage(cb: (m: EdgeMessage) => void): () => void {
    this.messageCbs.add(cb)
    return () => this.messageCbs.delete(cb)
  }

  onDisconnect(cb: () => void): void {
    this.disconnectCbs.add(cb)
  }

  /** Subscribe to connecting/open/closed transitions. Returns unsubscribe. */
  onStateChange(cb: (s: BridgeState) => void): () => void {
    this.stateCbs.add(cb)
    return () => this.stateCbs.delete(cb)
  }

  private emitState(s: BridgeState): void {
    this.stateCbs.forEach(cb => cb(s))
  }

  /** Intentional teardown: stop timers, close socket, suppress reconnect. */
  disconnect(): void {
    this.intentionalClose = true
    this.clearTimers()
    this.sessionId = ''
    if (this.ws) {
      this.teardownSocket(this.ws)
      try {
        this.ws.close()
      } catch {
        // ignore
      }
      this.ws = null
    }
    this.emitState('closed')
  }
}
