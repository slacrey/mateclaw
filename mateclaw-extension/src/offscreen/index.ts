// Offscreen document — owns the persistent edge WebSocket.
//
// See shared/offscreen-protocol.ts for WHY the socket lives here instead of the
// service worker. This module is a thin transport host: it runs a
// DirectBridgeClient (the real WS) and relays frames to/from the SW over
// chrome.runtime messaging. It performs NO privileged work — chrome.debugger /
// scripting / tabs are unavailable in an offscreen document and stay in the SW.

import { DirectBridgeClient } from '../sw/direct-bridge'
import type { EdgeMessage } from '../shared/edge-protocol'
import {
  OFFSCREEN_CONNECT,
  OFFSCREEN_DISCONNECT,
  OFFSCREEN_DISCONNECTED,
  OFFSCREEN_INBOUND,
  OFFSCREEN_SEND,
  OFFSCREEN_STATE,
  isOffscreenMsg,
  type OffscreenControlMsg,
} from '../shared/offscreen-protocol'

let bridge: DirectBridgeClient | null = null
let unsubMessage: (() => void) | null = null
let unsubState: (() => void) | null = null
/** `${serverUrl}\n${pat}` of the live socket — gates idempotent reconnects. */
let currentCreds: string | null = null

/** Push a relay envelope up to the SW. Best-effort: a suspended SW is woken by
 *  the message; "no receiver" only happens transiently and is safe to drop. */
function relayToSw(msg: unknown): void {
  chrome.runtime.sendMessage(msg).catch(() => {
    // No SW listener in this instant (rare) — the next frame/heartbeat re-syncs.
  })
}

function teardown(): void {
  unsubMessage?.()
  unsubMessage = null
  unsubState?.()
  unsubState = null
  try {
    bridge?.disconnect()
  } catch {
    // ignore
  }
  bridge = null
  currentCreds = null
}

function onConnect(msg: Extract<OffscreenControlMsg, { type: typeof OFFSCREEN_CONNECT }>): void {
  const creds = `${msg.serverUrl}\n${msg.pat}`
  // Idempotent: the SW re-runs its startup connect on EVERY wake. If we are
  // already connected with the same creds, don't churn the socket — just
  // re-announce OPEN so the freshly-restarted SW (which lost its in-memory
  // state) learns the live connection status for its pill + isConnected().
  if (bridge && currentCreds === creds && bridge.connected) {
    relayToSw({ type: OFFSCREEN_STATE, state: 'open', connected: true })
    return
  }
  teardown()
  currentCreds = creds
  const client = new DirectBridgeClient({
    deviceId: msg.deviceId,
    deviceName: msg.deviceName,
    agentVersion: msg.agentVersion,
  })
  bridge = client
  unsubMessage = client.onMessage((m: EdgeMessage) => {
    relayToSw({ type: OFFSCREEN_INBOUND, message: m })
  })
  unsubState = client.onStateChange(state => {
    relayToSw({ type: OFFSCREEN_STATE, state, connected: client.connected })
  })
  client.onDisconnect(() => {
    relayToSw({ type: OFFSCREEN_DISCONNECTED })
  })
  client.connect(msg.serverUrl, msg.pat)
}

chrome.runtime.onMessage.addListener((raw: unknown): undefined => {
  if (!isOffscreenMsg(raw)) return
  // Only SW→OFF control envelopes are actionable here; ignore our own relay
  // echoes and the SW's sidepanel `edge.inbound` broadcasts.
  switch (raw.type) {
    case OFFSCREEN_CONNECT:
      onConnect(raw)
      break
    case OFFSCREEN_SEND:
      try {
        bridge?.send(raw.message)
      } catch {
        // Bridge mid-reconnect — the server reissues on the next snapshot/action.
      }
      break
    case OFFSCREEN_DISCONNECT:
      teardown()
      break
    default:
      // OFFSCREEN_INBOUND / OFFSCREEN_STATE / OFFSCREEN_DISCONNECTED are OUR
      // outbound types; never handled here.
      break
  }
  return undefined
})
