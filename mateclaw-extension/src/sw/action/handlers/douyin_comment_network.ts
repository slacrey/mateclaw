import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DouyinCommentNetworkParams } from '../types'
import {
  SessionDetachedError,
  type DebuggerEvent,
  type DebuggerManager,
} from '../../debugger-manager'
import type {
  NetworkLoadingFailedEvent,
  NetworkLoadingFinishedEvent,
  NetworkResponse,
  NetworkResponseReceivedEvent,
} from '../../cdp-types'

export interface DouyinCommentNetworkHandlerDeps {
  debugger: DebuggerManager
  clock?: () => number
  setTimer?: (fn: () => void, ms: number) => unknown
  clearTimer?: (timer: unknown) => void
}

interface CaptureLimits {
  maxPages: number
  maxBodyBytes: number
  ttlMs: number
}

interface ResponseMeta {
  requestId: string
  url: string
  status: number
  mimeType?: string
  encodedDataLength?: number
  urlSignal: boolean
  likelyJson: boolean
}

interface CapturedPage {
  url: string
  requestId: string
  status: number
  body: string
  base64Encoded: boolean
  capturedAtMs: number
}

interface CaptureSession extends CaptureLimits {
  tabId: number
  pages: CapturedPage[]
  responses: Map<string, ResponseMeta>
  inflight: Set<Promise<void>>
  removeListener: () => void
  timer?: unknown
  expiresAtMs: number
  stopped: boolean
}

const DEFAULT_MAX_PAGES = 8
const MAX_PAGES = 50
const DEFAULT_MAX_BODY_BYTES = 256 * 1024
const MAX_BODY_BYTES = 1024 * 1024
const DEFAULT_TTL_MS = 45_000
const MAX_TTL_MS = 180_000
const DRAIN_INFLIGHT_WAIT_MS = 750
const MAX_PENDING_RESPONSES = 200

export const douyinCommentNetworkHandler = (
  deps: DouyinCommentNetworkHandlerDeps,
): ActionHandler<DouyinCommentNetworkParams> => {
  const clock = deps.clock ?? Date.now
  const setTimer = deps.setTimer ?? ((fn: () => void, ms: number) => setTimeout(fn, ms))
  const clearTimer = deps.clearTimer ?? (timer => clearTimeout(timer as ReturnType<typeof setTimeout>))
  const sessions = new Map<number, CaptureSession>()

  const cleanupLocal = (session: CaptureSession): void => {
    if (session.stopped) return
    session.stopped = true
    session.removeListener()
    session.responses.clear()
    session.inflight.clear()
    session.pages.length = 0
    if (session.timer !== undefined) {
      clearTimer(session.timer)
      session.timer = undefined
    }
    sessions.delete(session.tabId)
  }

  const stopCapture = async (
    tabId: number,
    options: { disableNetwork: boolean; throwOnDisableError: boolean },
  ): Promise<void> => {
    const session = sessions.get(tabId)
    let disableError: unknown

    if (options.disableNetwork && session) {
      try {
        await deps.debugger.send(tabId, 'Network.disable', {})
      } catch (err) {
        disableError = err
      }
    }

    if (session) cleanupLocal(session)

    if (options.throwOnDisableError && disableError) {
      throw mapDebuggerError(disableError)
    }
  }

  const startCapture = async (
    tabId: number,
    params: DouyinCommentNetworkParams,
  ) => {
    const limits = normalizeLimits(params)
    await stopCapture(tabId, { disableNetwork: true, throwOnDisableError: false })

    let session: CaptureSession | undefined
    try {
      await deps.debugger.attach(tabId)

      session = {
        tabId,
        ...limits,
        pages: [],
        responses: new Map(),
        inflight: new Set(),
        removeListener: () => {},
        expiresAtMs: clock() + limits.ttlMs,
        stopped: false,
      }
      session.removeListener = deps.debugger.addEventListener(tabId, event => {
        handleDebuggerEvent(session!, event)
      })
      session.timer = setTimer(() => {
        void stopCapture(tabId, { disableNetwork: true, throwOnDisableError: false })
      }, limits.ttlMs)
      sessions.set(tabId, session)

      await deps.debugger.send(tabId, 'Network.enable', {
        maxResourceBufferSize: limits.maxBodyBytes,
        maxTotalBufferSize: Math.min(limits.maxBodyBytes * limits.maxPages, 4 * 1024 * 1024),
        maxPostDataSize: 0,
      })

      return {
        ok: true as const,
        elapsed_ms: 0,
        payload: {
          op: 'start',
          capturing: true,
          maxPages: limits.maxPages,
          maxBodyBytes: limits.maxBodyBytes,
          ttlMs: limits.ttlMs,
          expiresAtMs: session.expiresAtMs,
        },
      }
    } catch (err) {
      if (session) cleanupLocal(session)
      throw mapDebuggerError(err)
    }
  }

  const drainCapture = async (tabId: number, deadlineMs: number) => {
    const session = sessions.get(tabId)
    if (!session) {
      return {
        ok: true as const,
        elapsed_ms: 0,
        payload: {
          op: 'drain',
          pages: [],
          drainedCount: 0,
          capturing: false,
        },
      }
    }

    await settleInflight(session, deadlineMs)
    const pages = session.pages.splice(0, session.pages.length)

    return {
      ok: true as const,
      elapsed_ms: 0,
      payload: {
        op: 'drain',
        pages,
        drainedCount: pages.length,
        capturing: !session.stopped,
        expiresAtMs: session.expiresAtMs,
      },
    }
  }

  const stopCaptureAction = async (tabId: number) => {
    const session = sessions.get(tabId)
    const droppedCount = session?.pages.length ?? 0
    await stopCapture(tabId, { disableNetwork: true, throwOnDisableError: true })

    return {
      ok: true as const,
      elapsed_ms: 0,
      payload: {
        op: 'stop',
        stopped: true,
        droppedCount,
      },
    }
  }

  const handleDebuggerEvent = (session: CaptureSession, event: DebuggerEvent): void => {
    if (session.stopped || event.tabId !== session.tabId) return
    if (clock() >= session.expiresAtMs) {
      void stopCapture(session.tabId, { disableNetwork: true, throwOnDisableError: false })
      return
    }

    if (event.method === 'Network.responseReceived') {
      rememberResponse(session, event.params as NetworkResponseReceivedEvent)
      return
    }
    if (event.method === 'Network.loadingFinished') {
      const work = captureFinishedResponse(session, event.params as NetworkLoadingFinishedEvent).catch(() => {})
      session.inflight.add(work)
      work.finally(() => session.inflight.delete(work))
      return
    }
    if (event.method === 'Network.loadingFailed') {
      const params = event.params as NetworkLoadingFailedEvent
      if (typeof params.requestId === 'string') session.responses.delete(params.requestId)
    }
  }

  const rememberResponse = (session: CaptureSession, event: NetworkResponseReceivedEvent): void => {
    const requestId = event?.requestId
    const response = event?.response
    if (typeof requestId !== 'string' || !isNetworkResponse(response)) return

    const urlSignal = urlLooksLikeDouyinCommentResponse(response.url)
    const likelyJson = responseLooksJson(response)
    if (!urlSignal && (!likelyJson || !urlIsDouyinRelated(response.url))) return

    session.responses.set(requestId, {
      requestId,
      url: response.url,
      status: normalizeStatus(response.status),
      mimeType: response.mimeType,
      encodedDataLength: finiteNumber(response.encodedDataLength),
      urlSignal,
      likelyJson,
    })

    while (session.responses.size > MAX_PENDING_RESPONSES) {
      const oldest = session.responses.keys().next().value
      if (typeof oldest !== 'string') break
      session.responses.delete(oldest)
    }
  }

  const captureFinishedResponse = async (
    session: CaptureSession,
    event: NetworkLoadingFinishedEvent,
  ): Promise<void> => {
    const requestId = event?.requestId
    if (typeof requestId !== 'string') return

    const meta = session.responses.get(requestId)
    session.responses.delete(requestId)
    if (!meta || session.stopped || session.pages.length >= session.maxPages) return

    const encodedDataLength = finiteNumber(event.encodedDataLength) ?? meta.encodedDataLength
    if (encodedDataLength !== undefined && encodedDataLength > session.maxBodyBytes) return
    if (!meta.urlSignal && !meta.likelyJson) return

    let bodyResult: { body: string; base64Encoded: boolean }
    try {
      bodyResult = await deps.debugger.send(session.tabId, 'Network.getResponseBody', { requestId })
    } catch {
      return
    }

    if (session.stopped || session.pages.length >= session.maxPages) return
    if (typeof bodyResult.body !== 'string') return
    const base64Encoded = bodyResult.base64Encoded === true
    if (bodyByteLength(bodyResult.body, base64Encoded) > session.maxBodyBytes) return
    if (!meta.urlSignal && !bodyLooksLikeDouyinCommentJson(bodyResult.body, base64Encoded)) return

    session.pages.push({
      url: meta.url,
      requestId: meta.requestId,
      status: meta.status,
      body: bodyResult.body,
      base64Encoded,
      capturedAtMs: clock(),
    })
  }

  return async (tabId, params, deadlineMs) => {
    switch (params?.op) {
      case 'start':
        return startCapture(tabId, params)
      case 'drain':
        return drainCapture(tabId, deadlineMs)
      case 'stop':
        return stopCaptureAction(tabId)
      default:
        throw new ActionFailureError(
          'HANDLER_ERROR',
          "douyin_comment_network op must be 'start', 'drain', or 'stop'",
          false,
        )
    }
  }
}

function normalizeLimits(params: DouyinCommentNetworkParams): CaptureLimits {
  return {
    maxPages: boundedInteger(params.maxPages, DEFAULT_MAX_PAGES, 1, MAX_PAGES),
    maxBodyBytes: boundedInteger(params.maxBodyBytes, DEFAULT_MAX_BODY_BYTES, 1, MAX_BODY_BYTES),
    ttlMs: boundedInteger(params.ttlMs, DEFAULT_TTL_MS, 1_000, MAX_TTL_MS),
  }
}

function boundedInteger(value: unknown, fallback: number, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback
  return Math.min(max, Math.max(min, Math.floor(value)))
}

async function settleInflight(session: CaptureSession, deadlineMs: number): Promise<void> {
  const pending = [...session.inflight]
  if (pending.length === 0) return
  const waitMs = Math.min(
    DRAIN_INFLIGHT_WAIT_MS,
    Math.max(0, Math.floor(Number.isFinite(deadlineMs) ? deadlineMs : DRAIN_INFLIGHT_WAIT_MS)),
  )
  await Promise.race([
    Promise.allSettled(pending),
    new Promise(resolve => setTimeout(resolve, waitMs)),
  ])
}

function mapDebuggerError(err: unknown): Error {
  if (!(err instanceof SessionDetachedError)) {
    return err instanceof Error ? err : new Error(String(err))
  }

  if (err.reason === 'target_closed') {
    return new ActionFailureError('NO_TARGET_TAB', err.message, false)
  }
  if (err.reason === 'canceled_by_user' || err.reason === 'replaced_with_devtools') {
    return new ActionFailureError('DEVTOOLS_OPEN', err.message, true)
  }
  return new ActionFailureError('SESSION_DETACHED', err.message, true)
}

function isNetworkResponse(value: unknown): value is NetworkResponse {
  if (!value || typeof value !== 'object') return false
  const response = value as Partial<NetworkResponse>
  return typeof response.url === 'string' && typeof response.status === 'number'
}

function normalizeStatus(value: number): number {
  return Number.isFinite(value) ? Math.round(value) : 0
}

function finiteNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function responseLooksJson(response: NetworkResponse): boolean {
  const mime = String(response.mimeType || '').toLowerCase()
  if (mime.includes('json')) return true
  const headers = response.headers ?? {}
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === 'content-type' && String(value).toLowerCase().includes('json')) {
      return true
    }
  }
  return false
}

function urlLooksLikeDouyinCommentResponse(rawUrl: string): boolean {
  const lower = safeDecode(rawUrl).toLowerCase()
  if (!urlIsDouyinRelated(rawUrl)) return false

  const hasComment = lower.includes('comment')
  const hasCommentList = lower.includes('comment/list') ||
    lower.includes('comment%2flist') ||
    lower.includes('/comment/list')
  const hasContext = lower.includes('cursor') ||
    lower.includes('has_more') ||
    lower.includes('aweme') ||
    lower.includes('reply')

  return hasCommentList || (hasComment && hasContext)
}

function urlIsDouyinRelated(rawUrl: string): boolean {
  const host = safeHost(rawUrl)
  return host.includes('douyin.com') ||
    host.includes('iesdouyin.com') ||
    host.includes('amemv.com')
}

function bodyLooksLikeDouyinCommentJson(body: string, base64Encoded: boolean): boolean {
  if (base64Encoded) return false
  const trimmed = body.trim()
  if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) return false

  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return false
  }

  const signals = collectJsonSignals(parsed)
  const commentish = signals.comment || signals.commentList || signals.commentId
  const paginationOrAweme = signals.cursor || signals.hasMore || signals.aweme || signals.list
  return commentish && paginationOrAweme
}

interface JsonSignals {
  comment: boolean
  commentList: boolean
  commentId: boolean
  cursor: boolean
  hasMore: boolean
  aweme: boolean
  list: boolean
}

function collectJsonSignals(root: unknown): JsonSignals {
  const signals: JsonSignals = {
    comment: false,
    commentList: false,
    commentId: false,
    cursor: false,
    hasMore: false,
    aweme: false,
    list: false,
  }
  let visited = 0

  const visit = (value: unknown, depth: number): void => {
    if (visited > 400 || depth > 6 || value == null) return
    visited += 1

    if (Array.isArray(value)) {
      for (const item of value.slice(0, 40)) visit(item, depth + 1)
      return
    }

    if (typeof value !== 'object') return
    for (const [key, child] of Object.entries(value as Record<string, unknown>).slice(0, 80)) {
      markKey(key, signals)
      visit(child, depth + 1)
    }
  }

  visit(root, 0)
  return signals
}

function markKey(rawKey: string, signals: JsonSignals): void {
  const key = rawKey.toLowerCase()
  if (key.includes('comment')) signals.comment = true
  if (key.includes('comment_list') || key.includes('comments')) signals.commentList = true
  if (key === 'cid' || key.includes('comment_id')) signals.commentId = true
  if (key === 'cursor' || key.endsWith('_cursor') || key.includes('cursor')) signals.cursor = true
  if (key === 'has_more' || key === 'hasmore' || key.includes('has_more')) signals.hasMore = true
  if (key.includes('aweme')) signals.aweme = true
  if (key === 'list' || key.endsWith('_list')) signals.list = true
}

function bodyByteLength(body: string, base64Encoded: boolean): number {
  if (base64Encoded) return Math.floor((body.replace(/=+$/u, '').length * 3) / 4)
  const encoder = typeof TextEncoder !== 'undefined' ? new TextEncoder() : undefined
  if (encoder) return encoder.encode(body).byteLength
  return [...body].reduce((total, char) => {
    const code = char.codePointAt(0) ?? 0
    if (code <= 0x7f) return total + 1
    if (code <= 0x7ff) return total + 2
    if (code <= 0xffff) return total + 3
    return total + 4
  }, 0)
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function safeHost(value: string): string {
  try {
    return new URL(value).hostname.toLowerCase()
  } catch {
    return value.toLowerCase()
  }
}
