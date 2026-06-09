// Control + relay protocol between the service worker and the offscreen
// document that owns the edge WebSocket.
//
// Why an offscreen document holds the socket: a WebSocket opened inside the MV3
// service worker dies when Chrome suspends the SW after ~30s idle — that was the
// "中途断线重连" the user saw mid-task. An offscreen document is an ordinary page
// context that is NOT subject to the SW idle timer, so the socket survives there.
// All privileged work (chrome.debugger / scripting / tabs) still happens in the
// SW, which is unavailable to the offscreen doc — so the two split as:
//
//   transport (socket)  ── offscreen document
//   work (CDP, tabs)    ── service worker
//
// and they exchange Edge frames over chrome.runtime messaging. Because an
// inbound chrome.runtime message wakes a suspended SW, a frame arriving on the
// socket (in the offscreen doc) revives the SW to handle it — the socket no
// longer has to keep the SW alive at all.
//
// Direction legend below is SW = service worker, OFF = offscreen document.

import type { EdgeMessage } from './edge-protocol'
import type { BridgeState } from '../sw/direct-bridge'

/** SW → OFF: open (or replace) the socket with these credentials. */
export const OFFSCREEN_CONNECT = 'mateclaw.offscreen.connect' as const
/** SW → OFF: send an Edge frame over the socket. */
export const OFFSCREEN_SEND = 'mateclaw.offscreen.send' as const
/** SW → OFF: tear the socket down and suppress reconnect. */
export const OFFSCREEN_DISCONNECT = 'mateclaw.offscreen.disconnect' as const

/** OFF → SW: an Edge frame arrived on the socket (also wakes a suspended SW). */
export const OFFSCREEN_INBOUND = 'mateclaw.offscreen.inbound' as const
/** OFF → SW: connection state changed (drives the sidepanel pill + `connected`). */
export const OFFSCREEN_STATE = 'mateclaw.offscreen.state' as const
/** OFF → SW: the socket closed (mirrors DirectBridgeClient.onDisconnect). */
export const OFFSCREEN_DISCONNECTED = 'mateclaw.offscreen.disconnected' as const

/** SW → OFF control envelopes. */
export type OffscreenControlMsg =
  | {
      type: typeof OFFSCREEN_CONNECT
      serverUrl: string
      pat: string
      deviceId: string
      deviceName?: string
      agentVersion: string
    }
  | { type: typeof OFFSCREEN_SEND; message: EdgeMessage }
  | { type: typeof OFFSCREEN_DISCONNECT }

/** OFF → SW relay envelopes. */
export type OffscreenRelayMsg =
  | { type: typeof OFFSCREEN_INBOUND; message: EdgeMessage }
  | { type: typeof OFFSCREEN_STATE; state: BridgeState; connected: boolean }
  | { type: typeof OFFSCREEN_DISCONNECTED }

/** Type guard for any envelope this protocol defines (control or relay). */
export function isOffscreenMsg(v: unknown): v is OffscreenControlMsg | OffscreenRelayMsg {
  if (!v || typeof v !== 'object') return false
  const t = (v as { type?: unknown }).type
  return (
    t === OFFSCREEN_CONNECT ||
    t === OFFSCREEN_SEND ||
    t === OFFSCREEN_DISCONNECT ||
    t === OFFSCREEN_INBOUND ||
    t === OFFSCREEN_STATE ||
    t === OFFSCREEN_DISCONNECTED
  )
}
