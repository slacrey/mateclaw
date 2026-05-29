import { EdgeMessageKind, makeEdgeMessage, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'

const MAX_BASE64_LENGTH = 500_000

type ScreenshotErrorCode = 'NO_TARGET_TAB' | 'SCREENSHOT_TOO_LARGE' | 'PERMISSION_DENIED'

export interface ScreenshotCaptureHandlerDeps {
  resolver: TabRefResolver
  chrome?: typeof globalThis.chrome
  sendUp: (msg: EdgeMessage) => void
  uuid?: () => string
  clock?: () => number
}

export class ScreenshotCaptureHandler {
  constructor(private readonly deps: ScreenshotCaptureHandlerDeps) {}

  async handle(msg: EdgeMessage): Promise<void> {
    if (msg.kind !== EdgeMessageKind.ScreenshotCaptureRequest) return

    const payload = msg.payload ?? {}
    const tabRef = parseTabRef(payload.tab_ref)
    const snapshotId = this.uuid()
    const capturedAt = this.clock()

    let tabId: number | null = null
    if (tabRef !== null) {
      try {
        tabId = await this.deps.resolver.resolve(tabRef)
      } catch (err) {
        this.respondError(
          msg,
          snapshotId,
          capturedAt,
          'NO_TARGET_TAB',
          `tab_ref resolution threw: ${errorMessage(err)}`,
        )
        return
      }
    }

    if (tabId === null) {
      this.respondError(
        msg,
        snapshotId,
        capturedAt,
        'NO_TARGET_TAB',
        `tab_ref ${String(payload.tab_ref)} not resolved`,
      )
      return
    }

    try {
      // chrome.tabs.captureVisibleTab is per-window; Phase 3 scope is a
      // single-window bridge, so target the current window sentinel.
      const dataUrl = await this.chrome().tabs.captureVisibleTab(-1, {
        format: 'png',
      })
      const base64 = String(dataUrl).split(',')[1] ?? ''
      if (base64.length > MAX_BASE64_LENGTH) {
        this.respondError(
          msg,
          snapshotId,
          capturedAt,
          'SCREENSHOT_TOO_LARGE',
          `payload ${base64.length} bytes exceeds ${MAX_BASE64_LENGTH}`,
        )
        return
      }

      const viewport = await this.getViewport(tabId)
      this.deps.sendUp(makeEdgeMessage({
        kind: EdgeMessageKind.ScreenshotCaptureResponse,
        traceId: msg.trace_id,
        inReplyTo: msg.msg_id,
        payload: {
          snapshot_id: snapshotId,
          captured_at_ms: capturedAt,
          tab_ref: tabId,
          format: 'png',
          data_base64: base64,
          viewport,
          actual_dimensions: viewport,
        },
      }))
    } catch (err) {
      this.respondError(
        msg,
        snapshotId,
        capturedAt,
        'PERMISSION_DENIED',
        errorMessage(err),
      )
    }
  }

  private async getViewport(tabId: number): Promise<{ w: number; h: number }> {
    const tab = await this.chrome().tabs.get(tabId)
    return { w: tab.width ?? 1280, h: tab.height ?? 800 }
  }

  private respondError(
    req: EdgeMessage,
    snapshotId: string,
    capturedAt: number,
    code: ScreenshotErrorCode,
    message: string,
  ): void {
    this.deps.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.ScreenshotCaptureResponse,
      traceId: req.trace_id,
      inReplyTo: req.msg_id,
      payload: {
        snapshot_id: snapshotId,
        captured_at_ms: capturedAt,
        tab_ref: -1,
        error: { code, message },
      },
    }))
  }

  private uuid(): string {
    return (this.deps.uuid ?? crypto.randomUUID.bind(crypto))()
  }

  private clock(): number {
    return (this.deps.clock ?? Date.now)()
  }

  private chrome(): typeof globalThis.chrome {
    return this.deps.chrome ?? globalThis.chrome
  }
}

function parseTabRef(value: unknown): TabRef | null {
  if (value === 'main' || value === 'active') return value
  if (typeof value === 'number' && Number.isInteger(value)) return value
  return null
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}
