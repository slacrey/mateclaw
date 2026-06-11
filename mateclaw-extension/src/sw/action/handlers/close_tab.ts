import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { CloseTabParams } from '../types'

export interface CloseTabHandlerDeps {
  chrome?: typeof globalThis.chrome
  clock?: () => number
}

export const closeTabHandler = (deps: CloseTabHandlerDeps = {}): ActionHandler<CloseTabParams> => {
  const chrome = deps.chrome ?? globalThis.chrome
  const clock = deps.clock ?? Date.now

  return async (tabId) => {
    const startedAt = clock()
    if (typeof tabId !== 'number' || tabId <= 0) {
      throw new ActionFailureError('NO_TARGET_TAB', 'close_tab requires a concrete tab id', false)
    }
    if (typeof chrome?.tabs?.remove !== 'function') {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.tabs.remove is unavailable', false)
    }
    try {
      await chrome.tabs.remove(tabId)
      return {
        ok: true,
        elapsed_ms: Math.max(0, clock() - startedAt),
        payload: { tabId },
      }
    } catch (err) {
      throw new ActionFailureError('NO_TARGET_TAB', errorMessage(err), false)
    }
  }
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}
