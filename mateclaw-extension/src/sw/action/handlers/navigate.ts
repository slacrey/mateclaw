import type { ActionHandler } from '../ActionExecutor'
import { ActionFailureError } from '../ActionExecutor'
import type { NavigateParams } from '../types'

type LoadState = NonNullable<NavigateParams['wait_for']>
type WebNavigationDetails = chrome.webNavigation.WebNavigationFramedCallbackDetails
type WebRequestDetails = chrome.webRequest.WebRequestBodyDetails

const NETWORK_IDLE_MS = 500

/**
 * navigate handler.
 *
 * Top-level navigation uses chrome.tabs.update(). The handler then waits for
 * the requested page-load signal, while racing deadline expiry and tab close.
 */
export const navigateHandler = (
  chromeApi: typeof globalThis.chrome = chrome,
): ActionHandler<NavigateParams> => {
  return async (tabId, params, deadlineMs) => {
    const waitFor: LoadState = params.wait_for ?? 'load'
    let timeoutId: ReturnType<typeof setTimeout> | undefined
    let idleTimeoutId: ReturnType<typeof setTimeout> | undefined
    let completedListener: ((details: WebNavigationDetails) => void) | undefined
    let dclListener: ((details: WebNavigationDetails) => void) | undefined
    let requestListener: ((details: WebRequestDetails) => void) | undefined
    let removedListener: ((removedTabId: number) => void) | undefined

    const clearTimer = (id: ReturnType<typeof setTimeout> | undefined) => {
      if (id !== undefined) clearTimeout(id)
    }

    const readFinalUrl = async () => {
      const tab = await chromeApi.tabs.get(tabId)
      if (!tab) {
        throw new ActionFailureError(
          'NO_TARGET_TAB',
          `target tab ${tabId} is not available after navigation`,
          false,
        )
      }
      return tab.url ?? tab.pendingUrl ?? params.url
    }

    const removeListeners = () => {
      if (completedListener) {
        chromeApi.webNavigation.onCompleted.removeListener(completedListener)
        completedListener = undefined
      }
      if (dclListener) {
        chromeApi.webNavigation.onDOMContentLoaded.removeListener(dclListener)
        dclListener = undefined
      }
      if (requestListener) {
        chromeApi.webRequest?.onBeforeRequest?.removeListener(requestListener)
        requestListener = undefined
      }
      if (removedListener) {
        chromeApi.tabs.onRemoved.removeListener(removedListener)
        removedListener = undefined
      }
    }

    const deadlinePromise = new Promise<never>((_, reject) => {
      timeoutId = setTimeout(() => {
        reject(new ActionFailureError(
          'TIMEOUT_PAGE_LOAD',
          `navigation to ${params.url} timed out after ${deadlineMs}ms`,
          true,
        ))
      }, Math.max(0, deadlineMs))
    })

    const tabRemovedPromise = new Promise<never>((_, reject) => {
      removedListener = (removedTabId: number) => {
        if (removedTabId !== tabId) return
        reject(new ActionFailureError(
          'NO_TARGET_TAB',
          `target tab ${tabId} was closed during navigation`,
          false,
        ))
      }
      chromeApi.tabs.onRemoved.addListener(removedListener)
    })

    const waitForNavigation = () => {
      if (waitFor === 'none') return Promise.resolve()

      return new Promise<void>(resolve => {
        const isTopLevelTarget = (details: WebNavigationDetails | WebRequestDetails) => (
          details.tabId === tabId && details.frameId === 0
        )

        if (waitFor === 'domcontentloaded') {
          dclListener = (details: WebNavigationDetails) => {
            if (isTopLevelTarget(details)) resolve()
          }
          chromeApi.webNavigation.onDOMContentLoaded.addListener(dclListener)
          return
        }

        const resolveAfterIdle = () => {
          clearTimer(idleTimeoutId)
          idleTimeoutId = setTimeout(resolve, NETWORK_IDLE_MS)
        }

        completedListener = (details: WebNavigationDetails) => {
          if (!isTopLevelTarget(details)) return

          if (waitFor === 'network_idle') {
            resolveAfterIdle()
            return
          }

          resolve()
        }
        chromeApi.webNavigation.onCompleted.addListener(completedListener)

        if (waitFor === 'network_idle' && chromeApi.webRequest?.onBeforeRequest) {
          requestListener = (details: WebRequestDetails) => {
            if (isTopLevelTarget(details)) resolveAfterIdle()
          }
          chromeApi.webRequest.onBeforeRequest.addListener(
            requestListener,
            { tabId, urls: ['<all_urls>'] },
          )
        }
      })
    }

    try {
      const waitPromise = waitForNavigation()

      try {
        await chromeApi.tabs.update(tabId, { url: params.url })
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err)
        throw new ActionFailureError(
          'NO_TARGET_TAB',
          `unable to navigate target tab ${tabId}: ${message}`,
          false,
        )
      }

      await Promise.race([waitPromise, deadlinePromise, tabRemovedPromise])

      return {
        ok: true,
        elapsed_ms: 0,
        payload: {
          final_url: await readFinalUrl(),
          load_state: waitFor,
        },
      }
    } finally {
      clearTimer(timeoutId)
      clearTimer(idleTimeoutId)
      removeListeners()
    }
  }
}
