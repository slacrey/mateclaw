import {
  EdgeMessageKind,
  makeEdgeMessage,
  type EdgeMessage,
} from '../shared/edge-protocol'
import type { TabRef } from './action/types'
import type { TabRefResolver } from './action/tab-ref-resolver'

type VisualInternalMessage =
  | { type: 'SHOW_AGENT_INDICATORS'; isMcp?: boolean }
  | { type: 'HIDE_AGENT_INDICATORS' }
  | { type: 'INDICATOR_CURSOR'; x: number; y: number }
  | { type: 'TOOL_USE_HIDE' }
  | { type: 'TOOL_USE_SHOW' }

export interface VisualCoordinatorDeps {
  resolver: TabRefResolver
  /** Callback to send indicator.cursor action.result back via NativeBridge. */
  sendUp?: (msg: EdgeMessage) => void
  /** Chrome API; injectable for tests. */
  chrome?: typeof globalThis.chrome
}

/**
 * Forwards CP -> Ext indicator.* envelopes to the visual-indicator
 * content script via chrome.tabs.sendMessage.
 *
 * D2 owns the indicator.cursor round-trip: the content script's internal
 * sendMessage response is wrapped here as an action.result envelope. That
 * keeps Edge protocol emission in the service worker and leaves C5 focused
 * on visual DOM work only.
 */
export class VisualCoordinator {
  constructor(private readonly deps: VisualCoordinatorDeps) {}

  async handle(msg: EdgeMessage): Promise<void> {
    if (!VisualCoordinator.handles(msg.kind)) return

    const payload = msg.payload ?? {}
    const tabRef = parseTabRef(payload.tab_ref)
    if (tabRef === null) {
      console.warn('[mateclaw][sw] VisualCoordinator dropping envelope with invalid tab_ref', {
        kind: msg.kind,
        msg_id: msg.msg_id,
      })
      return
    }

    let tabId: number | null
    try {
      tabId = await this.deps.resolver.resolve(tabRef)
    } catch (err) {
      console.error('[mateclaw][sw] VisualCoordinator tab_ref resolution threw', err)
      return
    }

    if (tabId === null) {
      console.warn('[mateclaw][sw] VisualCoordinator could not resolve tab_ref', {
        kind: msg.kind,
        msg_id: msg.msg_id,
        tab_ref: tabRef,
      })
      return
    }

    const internalMessage = toInternalMessage(msg.kind, payload)
    if (!internalMessage) return

    const startedAt = Date.now()
    try {
      const response = await this.chrome().tabs.sendMessage(tabId, internalMessage)
      if (msg.kind === EdgeMessageKind.IndicatorCursor) {
        this.sendCursorResult(msg, response, Date.now() - startedAt)
      }
    } catch (err) {
      console.error('[mateclaw][sw] VisualCoordinator chrome.tabs.sendMessage failed', err)
    }
  }

  /** True if this kind should be routed by D2 (not by ActionRouter). */
  static handles(kind: EdgeMessageKind): boolean {
    return (
      kind === EdgeMessageKind.IndicatorShow ||
      kind === EdgeMessageKind.IndicatorHide ||
      kind === EdgeMessageKind.IndicatorCursor ||
      kind === EdgeMessageKind.IndicatorToolUseHide ||
      kind === EdgeMessageKind.IndicatorToolUseShow
    )
  }

  private chrome(): typeof globalThis.chrome {
    return this.deps.chrome ?? globalThis.chrome
  }

  private sendCursorResult(
    inbound: EdgeMessage,
    response: unknown,
    elapsedMs: number,
  ): void {
    if (!this.deps.sendUp) {
      console.warn('[mateclaw][sw] VisualCoordinator missing sendUp for indicator.cursor result')
      return
    }

    const r = responseAsRecord(response)
    const arrivedAtMs =
      typeof r?.arrived_at_ms === 'number' ? r.arrived_at_ms : Date.now()
    this.deps.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.ActionResult,
      traceId: inbound.trace_id,
      inReplyTo: inbound.msg_id,
      payload: {
        ok: true,
        elapsed_ms: elapsedMs,
        payload: { arrived_at_ms: arrivedAtMs },
      },
    }))
  }
}

function parseTabRef(value: unknown): TabRef | null {
  if (value === 'main' || value === 'active') return value
  if (typeof value === 'number' && Number.isInteger(value)) return value
  return null
}

function toInternalMessage(
  kind: EdgeMessageKind,
  payload: Record<string, unknown>,
): VisualInternalMessage | null {
  switch (kind) {
    case EdgeMessageKind.IndicatorShow:
      return {
        type: 'SHOW_AGENT_INDICATORS',
        isMcp: parseIsMcp(payload),
      }
    case EdgeMessageKind.IndicatorHide:
      return { type: 'HIDE_AGENT_INDICATORS' }
    case EdgeMessageKind.IndicatorCursor:
      return {
        type: 'INDICATOR_CURSOR',
        x: typeof payload.x === 'number' ? payload.x : 0,
        y: typeof payload.y === 'number' ? payload.y : 0,
      }
    case EdgeMessageKind.IndicatorToolUseHide:
      return { type: 'TOOL_USE_HIDE' }
    case EdgeMessageKind.IndicatorToolUseShow:
      return { type: 'TOOL_USE_SHOW' }
    default:
      return null
  }
}

function parseIsMcp(payload: Record<string, unknown>): boolean | undefined {
  if (typeof payload.isMcp === 'boolean') return payload.isMcp
  if (typeof payload.is_mcp === 'boolean') return payload.is_mcp
  return undefined
}

function responseAsRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object') return null
  return value as Record<string, unknown>
}
