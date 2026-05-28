// Canonical Edge protocol mirror for the Extension.
// See docs/specs/edge-protocol.md.
//
// AUDIT P0-1: The Extension MUST always emit session_id="" on outbound messages.
// Native Host is the sole owner of the server-issued session_id and will
// override any non-empty value it receives from stdin.

export const EdgeMessageKind = {
  Hello: 'hello',
  HelloAck: 'hello.ack',
  Heartbeat: 'heartbeat',
  HeartbeatAck: 'heartbeat.ack',
  Ping: 'ping',
  Pong: 'pong',
  Error: 'error',
  Unknown: '__unknown__',
} as const
export type EdgeMessageKind = (typeof EdgeMessageKind)[keyof typeof EdgeMessageKind]

const knownKinds = new Set<string>(Object.values(EdgeMessageKind))

export interface EdgeMessage {
  v: 1
  msg_id: string
  kind: EdgeMessageKind
  ts: number
  trace_id: string
  /** Always empty string from the Extension — NH is sole owner. */
  session_id: string
  in_reply_to?: string
  payload?: Record<string, unknown>
}

export function makeEdgeMessage(p: {
  kind: EdgeMessageKind
  /** Must be "" or omitted — Extension never sets a real session_id. */
  sessionId?: string
  traceId?: string
  inReplyTo?: string
  payload?: Record<string, unknown>
}): EdgeMessage {
  return {
    v: 1,
    msg_id: crypto.randomUUID(),
    kind: p.kind,
    ts: Date.now(),
    trace_id: p.traceId ?? crypto.randomUUID(),
    session_id: '',   // AUDIT P0-1: always empty; NH stamps the real id
    in_reply_to: p.inReplyTo,
    payload: p.payload ?? {},
  }
}

export function parseEdgeMessage(raw: string): EdgeMessage | null {
  let obj: unknown
  try {
    obj = JSON.parse(raw)
  } catch {
    return null
  }
  if (
    !obj || typeof obj !== 'object' ||
    (obj as Record<string, unknown>).v !== 1 ||
    typeof (obj as Record<string, unknown>).msg_id !== 'string' ||
    typeof (obj as Record<string, unknown>).kind !== 'string'
  ) {
    return null
  }
  const m = obj as EdgeMessage
  if (!knownKinds.has(m.kind)) {
    return { ...m, kind: EdgeMessageKind.Unknown }
  }
  return m
}
