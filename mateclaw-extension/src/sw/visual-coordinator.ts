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
  | { type: 'INDICATOR_HEARTBEAT' }

/** Default heartbeat cadence — fast enough that 3 missed heartbeats (15s)
 *  is still within typical user attention span; slow enough not to flood
 *  the message bus. */
export const DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000

type IntervalHandle = ReturnType<typeof setInterval>

export interface VisualCoordinatorDeps {
  resolver: TabRefResolver
  /** Callback to send indicator.cursor action.result back via NativeBridge. */
  sendUp?: (msg: EdgeMessage) => void
  /** Chrome API; injectable for tests. */
  chrome?: typeof globalThis.chrome
  /** Heartbeat publisher cadence in ms. Defaults to 5000. */
  heartbeatIntervalMs?: number
  /** Injectable scheduler (defaults to globalThis.setInterval). */
  scheduleInterval?: (cb: () => void, ms: number) => IntervalHandle
  /** Injectable cancel (defaults to globalThis.clearInterval). */
  cancelInterval?: (handle: IntervalHandle) => void
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
  /**
   * Per-tab heartbeat interval handle (Phase 2.1 D3).
   *
   * <p>While indicators are SHOWN on a tab, the SW pings INDICATOR_HEARTBEAT
   * every {@link DEFAULT_HEARTBEAT_INTERVAL_MS}. The content-script-side
   * watchdog auto-unmounts overlays after ~3 missed beats, so if the SW
   * is killed (MV3 idle eviction, crash) the user is not left staring at
   * a zombie cursor + glow border.
   */
  readonly #heartbeats = new Map<number, IntervalHandle>()

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
      // Phase 2.1 D3: maintain the per-tab heartbeat publisher.
      if (msg.kind === EdgeMessageKind.IndicatorShow) {
        this.#startHeartbeat(tabId)
      } else if (msg.kind === EdgeMessageKind.IndicatorHide) {
        this.#stopHeartbeat(tabId)
      }
    } catch (err) {
      console.error('[mateclaw][sw] VisualCoordinator chrome.tabs.sendMessage failed', err)
    }
  }

  /**
   * Drop every running heartbeat. Called at SW shutdown — and useful in
   * tests so beforeEach starts from a clean slate.
   */
  stopAllHeartbeats(): void {
    const cancel = this.deps.cancelInterval ?? clearInterval
    for (const handle of this.#heartbeats.values()) {
      cancel(handle as IntervalHandle)
    }
    this.#heartbeats.clear()
  }

  #startHeartbeat(tabId: number): void {
    // Idempotent — repeated SHOW envelopes are common (per-render rebroadcast
    // safety), and we don't want to leak multiple intervals on the same tab.
    if (this.#heartbeats.has(tabId)) return
    const schedule = this.deps.scheduleInterval ?? setInterval
    const intervalMs = this.deps.heartbeatIntervalMs ?? DEFAULT_HEARTBEAT_INTERVAL_MS
    const handle = schedule(() => {
      void this.#emitHeartbeat(tabId)
    }, intervalMs)
    this.#heartbeats.set(tabId, handle as IntervalHandle)
  }

  #stopHeartbeat(tabId: number): void {
    const handle = this.#heartbeats.get(tabId)
    if (handle === undefined) return
    const cancel = this.deps.cancelInterval ?? clearInterval
    cancel(handle)
    this.#heartbeats.delete(tabId)
  }

  async #emitHeartbeat(tabId: number): Promise<void> {
    try {
      await this.chrome().tabs.sendMessage(tabId, { type: 'INDICATOR_HEARTBEAT' })
    } catch (err) {
      // Tab gone (closed / navigated away to a CSP-disallowed page) — stop
      // pinging it. The next SHOW envelope (if any) starts a fresh interval.
      console.warn('[mateclaw][sw] VisualCoordinator heartbeat failed, stopping tab', { tabId, err })
      this.#stopHeartbeat(tabId)
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
