import type { CDP } from './cdp-types'

/**
 * Typed error thrown when a chrome.debugger session is detached
 * unexpectedly mid-action. Distinct reasons are mapped by action handlers to
 * NO_TARGET_TAB / DEVTOOLS_OPEN / SESSION_DETACHED at the wire level.
 */
export class SessionDetachedError extends Error {
  constructor(
    public readonly tabId: number,
    public readonly reason: DetachReason,
    detail?: string,
  ) {
    super(
      detail
        ? `debugger session for tab ${tabId} detached: ${reason}: ${detail}`
        : `debugger session for tab ${tabId} detached: ${reason}`,
    )
    this.name = 'SessionDetachedError'
  }
}

export type DetachReason =
  | 'target_closed'
  | 'canceled_by_user'
  | 'replaced_with_devtools'
  | 'unknown'

export class DebuggerManager {
  /** Per-tab attached state. Absence = not attached. */
  private readonly sessions = new Map<number, AttachedSession>()
  private readonly detachedReasons = new Map<number, DetachReason>()

  constructor(private readonly chrome: typeof globalThis.chrome) {
    this.chrome.debugger.onDetach.addListener(this.onDetach)
  }

  /**
   * Attach to a tab. Idempotent: re-attaching to an already attached tab
   * returns the existing session without issuing another CDP attach call.
   */
  async attach(tabId: number): Promise<void> {
    if (this.sessions.has(tabId)) return

    await new Promise<void>((resolve, reject) => {
      this.chrome.debugger.attach({ tabId }, '1.3', () => {
        const lastError = this.chrome.runtime.lastError
        if (lastError) {
          reject(new SessionDetachedError(tabId, reasonFromLastError(lastError.message), lastError.message))
          return
        }

        this.detachedReasons.delete(tabId)
        this.sessions.set(tabId, { tabId, pending: [] })
        resolve()
      })
    })
  }

  /**
   * Detach from a tab. No-op if not attached. Never throws while cleaning up.
   */
  async detach(tabId: number): Promise<void> {
    if (!this.sessions.has(tabId)) return

    await new Promise<void>(resolve => {
      this.chrome.debugger.detach({ tabId }, () => {
        this.sessions.delete(tabId)
        this.detachedReasons.delete(tabId)
        resolve()
      })
    })
  }

  /**
   * Send a CDP command on the attached session. Throws SessionDetachedError if
   * the session is gone so action handlers can bail out cleanly.
   */
  async send<M extends keyof CDP>(tabId: number, method: M, params: CDP[M]['params']): Promise<CDP[M]['result']> {
    const session = this.sessions.get(tabId)
    if (!session) {
      throw new SessionDetachedError(tabId, this.detachedReasons.get(tabId) ?? 'unknown')
    }

    return new Promise<CDP[M]['result']>((resolve, reject) => {
      const pending: PendingSend = {
        resolve: value => resolve(value as CDP[M]['result']),
        reject,
      }
      session.pending.push(pending)

      this.chrome.debugger.sendCommand({ tabId }, method as string, params, result => {
        removePending(session, pending)

        const lastError = this.chrome.runtime.lastError
        if (lastError) {
          reject(new SessionDetachedError(tabId, reasonFromLastError(lastError.message), lastError.message))
          return
        }

        resolve(result as CDP[M]['result'])
      })
    })
  }

  /** True if we have a live debugger session for this tab. */
  isAttached(tabId: number): boolean {
    return this.sessions.has(tabId)
  }

  private readonly onDetach = (source: chrome.debugger.Debuggee, reason: string) => {
    if (source.tabId == null) return

    const detachReason = normalizeReason(reason)
    this.detachedReasons.set(source.tabId, detachReason)

    const session = this.sessions.get(source.tabId)
    if (!session) return

    session.detachReason = detachReason
    session.pending.forEach(p => p.reject(new SessionDetachedError(source.tabId!, detachReason)))
    session.pending.length = 0
    this.sessions.delete(source.tabId)
  }
}

interface AttachedSession {
  tabId: number
  /** In-flight CDP send() promises so onDetach can reject them. */
  pending: PendingSend[]
  detachReason?: DetachReason
}

interface PendingSend {
  resolve: (v: unknown) => void
  reject: (e: Error) => void
}

function normalizeReason(raw: string): DetachReason {
  if (raw === 'target_closed') return 'target_closed'
  if (raw === 'canceled_by_user' || raw === 'replaced_with_devtools') return 'canceled_by_user'
  return 'unknown'
}

function reasonFromLastError(message: string | undefined): DetachReason {
  const lower = message?.toLowerCase() ?? ''
  if (lower.includes('debugger') || lower.includes('devtools') || lower.includes('user')) return 'canceled_by_user'
  if (lower.includes('target') || lower.includes('tab')) return 'target_closed'
  return 'unknown'
}

function removePending(session: AttachedSession, pending: PendingSend): void {
  const idx = session.pending.indexOf(pending)
  if (idx >= 0) session.pending.splice(idx, 1)
}
