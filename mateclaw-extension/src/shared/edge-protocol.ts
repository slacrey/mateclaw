// Canonical Edge protocol mirror for the Extension.
// See docs/specs/edge-protocol.md.
//
// AUDIT P0-1: The Extension MUST always emit session_id="" on outbound messages.
// Native Host is the sole owner of the server-issued session_id and will
// override any non-empty value it receives from stdin.

export const EdgeMessageKind = {
  // v1.0 — handshake + liveness
  Hello: 'hello',
  HelloAck: 'hello.ack',
  Heartbeat: 'heartbeat',
  HeartbeatAck: 'heartbeat.ack',
  Ping: 'ping',
  Pong: 'pong',
  Error: 'error',
  // v1.1 — atomic browser actions (Phase 2 P1)
  ActionExecute: 'action.execute',
  ActionResult: 'action.result',
  ActionCancel: 'action.cancel',
  // v1.1 — visual indicators
  IndicatorShow: 'indicator.show',
  IndicatorHide: 'indicator.hide',
  IndicatorCursor: 'indicator.cursor',
  IndicatorToolUseHide: 'indicator.tool_use_hide',
  IndicatorToolUseShow: 'indicator.tool_use_show',
  IndicatorStopClicked: 'indicator.stop_clicked',
  // v1.1 — accessibility tree snapshot
  A11ySnapshotRequest: 'a11y.snapshot.request',
  A11ySnapshotResponse: 'a11y.snapshot.response',
  // v1.2 — screenshot capture (Phase 3 T3.2)
  ScreenshotCaptureRequest: 'screenshot.capture.request',
  ScreenshotCaptureResponse: 'screenshot.capture.response',
  // v1.1 — unsolicited page-lifecycle events
  EventPageNavigated: 'event.page.navigated',
  EventTabClosed: 'event.tab.closed',
  // Sentinel
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
