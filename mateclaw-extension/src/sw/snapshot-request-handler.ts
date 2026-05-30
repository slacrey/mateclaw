import { EdgeMessageKind, makeEdgeMessage, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'

export interface SnapshotRequestHandlerDeps {
  resolver: TabRefResolver
  /** Chrome API; injectable for tests. Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
  /** Outbound bridge sender — same shape as ActionRouter's sendUp. */
  sendUp: (msg: EdgeMessage) => void
  /** snapshot_id factory (defaults to crypto.randomUUID). */
  uuid?: () => string
  /** captured_at_ms source (defaults to Date.now). */
  clock?: () => number
}

type SnapshotFilter = 'interactive' | 'all' | 'default'
const DEFAULT_DEPTH = 15
const DEFAULT_MAX_CHARS = 200000

interface SnapshotRequestPayload {
  tab_ref: TabRef
  filter: SnapshotFilter
  depth: number
  max_chars: number
  ref_id?: string
  frame_id?: number
}

interface SnapshotResult {
  tree: string
  viewport: { w: number; h: number }
}

/**
 * Handles inbound a11y.snapshot.request envelopes.
 */
export class SnapshotRequestHandler {
  constructor(private readonly deps: SnapshotRequestHandlerDeps) {}

  async handle(msg: EdgeMessage): Promise<void> {
    const req = parseSnapshotRequest(msg.payload)
    if (!req) {
      this.sendFailure(msg, -1, 'SNAPSHOT_FAILED', 'a11y.snapshot.request payload was malformed', false)
      return
    }

    const snapshotId = this.uuid()
    const capturedAtMs = this.clock()

    let tabId: number | null
    try {
      tabId = await this.deps.resolver.resolve(req.tab_ref)
    } catch (err) {
      this.sendFailure(
        msg,
        -1,
        'NO_TARGET_TAB',
        `tab_ref resolution threw: ${errorMessage(err)}`,
        true,
        snapshotId,
        capturedAtMs,
      )
      return
    }

    if (tabId === null) {
      this.sendFailure(
        msg,
        -1,
        'NO_TARGET_TAB',
        `could not resolve tab_ref=${JSON.stringify(req.tab_ref)}`,
        false,
        snapshotId,
        capturedAtMs,
      )
      return
    }

    let snapshot: SnapshotResult
    try {
      snapshot = await this.captureSnapshot(tabId, req)
    } catch (err) {
      this.sendFailure(
        msg,
        tabId,
        'SNAPSHOT_FAILED',
        errorMessage(err),
        true,
        snapshotId,
        capturedAtMs,
      )
      return
    }

    this.sendResponse(msg, {
      snapshot_id: snapshotId,
      captured_at_ms: capturedAtMs,
      tab_ref: tabId,
      tree: snapshot.tree,
      viewport: snapshot.viewport,
    })
  }

  private async captureSnapshot(tabId: number, req: SnapshotRequestPayload): Promise<SnapshotResult> {
    const chrome = this.deps.chrome ?? globalThis.chrome
    const target = req.frame_id === undefined
      ? { tabId, allFrames: false }
      : { tabId, frameIds: [req.frame_id] }
    // Ensure window.__mateclaw_a11y_tree exists before extracting. The manifest
    // content script may not have run yet — document_idle races the navigate
    // load event, the tab may have started as about:blank, or it predates the
    // extension. Programmatic injection is idempotent (the content script no-ops
    // if already present) and guarantees the extractor is available, instead of
    // failing with "not available" + an empty/zero-viewport snapshot.
    try {
      await chrome.scripting.executeScript({ target, files: ['content/a11y-tree.js'] })
    } catch {
      // best-effort — the func below surfaces a clear error if still missing
    }
    const results = await chrome.scripting.executeScript({
      target,
      func: (filter, depth, maxChars, refId, frameId) => {
        // The arg types come back loose (string|number|undefined) — the
        // call-site contract guarantees correct concrete types; assert.
        if (typeof frameId === 'number') {
          ;(window as Window & { __mateclaw_a11y_frame_id?: number }).__mateclaw_a11y_frame_id = frameId
        }
        const requestedRefId = typeof refId === 'string' ? refId : undefined
        const tree = window.__mateclaw_a11y_tree?.(
          filter as 'interactive' | 'all' | 'default',
          depth as number,
          maxChars as number,
          requestedRefId,
        )
        if (typeof tree !== 'string') {
          throw new Error('window.__mateclaw_a11y_tree is not available')
        }
        // innerWidth/Height can be 0 on a freshly-created tab whose renderer
        // hasn't laid out yet (observe right after navigate). Fall back to the
        // document client size, then a sane default, so the server's positive-
        // viewport validation doesn't reject an otherwise-valid tree.
        const vw = window.innerWidth || document.documentElement?.clientWidth || 1280
        const vh = window.innerHeight || document.documentElement?.clientHeight || 800
        return {
          tree,
          viewport: { w: vw, h: vh },
        }
      },
      args: [req.filter, req.depth, req.max_chars, req.ref_id ?? null, req.frame_id ?? null],
    })
    const first = results[0]?.result
    if (!isSnapshotResult(first)) {
      throw new Error('a11y snapshot injection returned an invalid result')
    }
    return first
  }

  private sendResponse(inbound: EdgeMessage, payload: Record<string, unknown>): void {
    this.deps.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.A11ySnapshotResponse,
      traceId: inbound.trace_id,
      inReplyTo: inbound.msg_id,
      payload,
    }))
  }

  private sendFailure(
    inbound: EdgeMessage,
    tabId: number,
    code: 'NO_TARGET_TAB' | 'SNAPSHOT_FAILED',
    message: string,
    retryable: boolean,
    snapshotId = this.uuid(),
    capturedAtMs = this.clock(),
  ): void {
    // The published snapshot response shape is not a Success/Failure union.
    // For typed failures, keep the a11y.snapshot.response envelope and attach
    // an error object beside an empty tree + zero viewport so CP can still
    // correlate freshness by resolved tab id when one exists.
    this.sendResponse(inbound, {
      snapshot_id: snapshotId,
      captured_at_ms: capturedAtMs,
      tab_ref: tabId,
      tree: '',
      viewport: { w: 0, h: 0 },
      error: { code, message, retryable },
    })
  }

  private uuid(): string {
    // crypto.randomUUID requires `this === crypto`; `(x ?? crypto.randomUUID)()`
    // calls it detached → "Illegal invocation". Call it with its receiver.
    return this.deps.uuid ? this.deps.uuid() : crypto.randomUUID()
  }

  private clock(): number {
    return this.deps.clock ? this.deps.clock() : Date.now()
  }
}

function parseSnapshotRequest(payload: unknown): SnapshotRequestPayload | null {
  if (!payload || typeof payload !== 'object') return null
  const p = payload as Record<string, unknown>

  if (p.tab_ref !== 'main' && p.tab_ref !== 'active' && typeof p.tab_ref !== 'number') return null
  if (p.filter !== 'interactive' && p.filter !== 'all' && p.filter !== 'default') return null
  if (p.depth !== undefined && typeof p.depth !== 'number') return null
  if (p.max_chars !== undefined && typeof p.max_chars !== 'number') return null
  if (p.ref_id !== undefined && typeof p.ref_id !== 'string') return null
  if (p.frame_id !== undefined || 'frame_id' in p) {
    if (typeof p.frame_id !== 'number') return null
    if (!Number.isInteger(p.frame_id) || p.frame_id < 0) return null
  }

  return {
    ...p,
    depth: typeof p.depth === 'number' ? p.depth : DEFAULT_DEPTH,
    max_chars: typeof p.max_chars === 'number' ? p.max_chars : DEFAULT_MAX_CHARS,
  } as unknown as SnapshotRequestPayload
}

function isSnapshotResult(value: unknown): value is SnapshotResult {
  if (!value || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  if (typeof v.tree !== 'string') return false
  if (!v.viewport || typeof v.viewport !== 'object') return false
  const viewport = v.viewport as Record<string, unknown>
  return typeof viewport.w === 'number' && typeof viewport.h === 'number'
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}
