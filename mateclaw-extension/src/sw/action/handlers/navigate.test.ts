import { describe, expect, it, vi } from 'vitest'
import { ActionFailureError } from '../ActionExecutor'
import { navigateHandler } from './navigate'

function removeListener<T>(listeners: T[], fn: T) {
  const index = listeners.indexOf(fn)
  if (index >= 0) listeners.splice(index, 1)
}

function captureRejection(promise: Promise<unknown>) {
  let caught: unknown
  const handled = promise.catch(err => {
    caught = err
  })
  return {
    handled,
    caught: () => caught,
  }
}

function fakeChrome() {
  const completedListeners: any[] = []
  const dclListeners: any[] = []
  const requestListeners: any[] = []
  const removedListeners: any[] = []
  const tabsState = new Map<number, chrome.tabs.Tab>()

  return {
    chrome: {
      tabs: {
        update: vi.fn(async (tabId: number, props: chrome.tabs.UpdateProperties) => {
          const tab = tabsState.get(tabId)
          if (!tab) throw new Error('No tab with id: ' + tabId)
          const next = { ...tab, url: props.url ?? tab.url, pendingUrl: props.url }
          tabsState.set(tabId, next)
          return next
        }),
        get: vi.fn(async (tabId: number) => tabsState.get(tabId)),
        onRemoved: {
          addListener: (fn: any) => removedListeners.push(fn),
          removeListener: (fn: any) => removeListener(removedListeners, fn),
        },
      },
      webNavigation: {
        onCompleted: {
          addListener: (fn: any) => completedListeners.push(fn),
          removeListener: (fn: any) => removeListener(completedListeners, fn),
        },
        onDOMContentLoaded: {
          addListener: (fn: any) => dclListeners.push(fn),
          removeListener: (fn: any) => removeListener(dclListeners, fn),
        },
      },
      webRequest: {
        onBeforeRequest: {
          addListener: (fn: any) => requestListeners.push(fn),
          removeListener: (fn: any) => removeListener(requestListeners, fn),
        },
      },
    } as unknown as typeof globalThis.chrome,
    triggerCompleted: (tabId: number, frameId: number, url: string) => {
      const tab = tabsState.get(tabId)
      if (tab && frameId === 0) tabsState.set(tabId, { ...tab, url, pendingUrl: undefined })
      completedListeners.slice().forEach(fn => fn({ tabId, frameId, url }))
    },
    triggerDCL: (tabId: number, frameId: number, url = 'https://example.com') => {
      const tab = tabsState.get(tabId)
      if (tab && frameId === 0) tabsState.set(tabId, { ...tab, url, pendingUrl: undefined })
      dclListeners.slice().forEach(fn => fn({ tabId, frameId, url }))
    },
    triggerRequest: (tabId: number, frameId = 0, url = 'https://example.com/asset.js') => {
      requestListeners.slice().forEach(fn => fn({ tabId, frameId, url }))
    },
    triggerTabRemoved: (tabId: number) => {
      tabsState.delete(tabId)
      removedListeners.slice().forEach(fn => fn(tabId, {}))
    },
    setTabState: (tab: chrome.tabs.Tab) => tabsState.set(tab.id!, tab),
    listenerCounts: () => ({
      completed: completedListeners.length,
      dcl: dclListeners.length,
      request: requestListeners.length,
      removed: removedListeners.length,
    }),
  }
}

describe('navigate handler', () => {
  it('happy path with wait_for=load -> returns Success with final_url', async () => {
    const f = fakeChrome()
    f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
    const navigate = navigateHandler(f.chrome)

    const promise = navigate(42, { url: 'https://example.com', wait_for: 'load' }, 5000)
    await vi.waitFor(() => expect(f.listenerCounts().completed).toBe(1))

    f.triggerCompleted(42, 0, 'https://example.com/')

    await expect(promise).resolves.toEqual({
      ok: true,
      elapsed_ms: 0,
      payload: {
        final_url: 'https://example.com/',
        load_state: 'load',
      },
    })
  })

  it('wait_for=domcontentloaded uses onDOMContentLoaded listener', async () => {
    const f = fakeChrome()
    f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
    const navigate = navigateHandler(f.chrome)

    const promise = navigate(42, { url: 'https://example.com', wait_for: 'domcontentloaded' }, 5000)
    await vi.waitFor(() => expect(f.listenerCounts().dcl).toBe(1))

    expect(f.listenerCounts().completed).toBe(0)
    f.triggerDCL(42, 0, 'https://example.com/ready')

    await expect(promise).resolves.toEqual({
      ok: true,
      elapsed_ms: 0,
      payload: {
        final_url: 'https://example.com/ready',
        load_state: 'domcontentloaded',
      },
    })
  })

  it('wait_for=network_idle waits for completed + 500ms idle window', async () => {
    vi.useFakeTimers()
    try {
      const f = fakeChrome()
      f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
      const navigate = navigateHandler(f.chrome)

      const promise = navigate(42, { url: 'https://example.com', wait_for: 'network_idle' }, 5000)
      await vi.waitFor(() => expect(f.listenerCounts().completed).toBe(1))

      f.triggerCompleted(42, 0, 'https://example.com/')
      await vi.advanceTimersByTimeAsync(499)
      let settled = false
      promise.then(() => { settled = true }, () => { settled = true })
      await Promise.resolve()
      expect(settled).toBe(false)

      f.triggerRequest(42, 9)
      await vi.advanceTimersByTimeAsync(499)
      await Promise.resolve()
      expect(settled).toBe(false)

      await vi.advanceTimersByTimeAsync(1)

      await expect(promise).resolves.toEqual({
        ok: true,
        elapsed_ms: 0,
        payload: {
          final_url: 'https://example.com/',
          load_state: 'network_idle',
        },
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('wait_for=none returns immediately after tabs.update', async () => {
    const f = fakeChrome()
    f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
    const navigate = navigateHandler(f.chrome)

    await expect(navigate(42, { url: 'https://example.com/instant', wait_for: 'none' }, 5000)).resolves.toEqual({
      ok: true,
      elapsed_ms: 0,
      payload: {
        final_url: 'https://example.com/instant',
        load_state: 'none',
      },
    })
    expect(f.chrome.tabs.update).toHaveBeenCalledExactlyOnceWith(42, { url: 'https://example.com/instant' })
    expect(f.listenerCounts()).toEqual({ completed: 0, dcl: 0, request: 0, removed: 0 })
  })

  it('deadline expiry -> throws ActionFailureError(TIMEOUT_PAGE_LOAD, retryable=true)', async () => {
    vi.useFakeTimers()
    try {
      const f = fakeChrome()
      f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
      const navigate = navigateHandler(f.chrome)

      const promise = navigate(42, { url: 'https://example.com', wait_for: 'load' }, 1000)
      const rejection = captureRejection(promise)
      await vi.waitFor(() => expect(f.listenerCounts().completed).toBe(1))
      await vi.advanceTimersByTimeAsync(1000)
      await rejection.handled

      expect(rejection.caught()).toMatchObject({
        code: 'TIMEOUT_PAGE_LOAD',
        retryable: true,
      })
      expect(rejection.caught()).toBeInstanceOf(ActionFailureError)
    } finally {
      vi.useRealTimers()
    }
  })

  it('tab closed mid-navigation -> throws ActionFailureError(NO_TARGET_TAB)', async () => {
    const f = fakeChrome()
    f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
    const navigate = navigateHandler(f.chrome)

    const promise = navigate(42, { url: 'https://example.com', wait_for: 'load' }, 5000)
    const rejection = captureRejection(promise)
    await vi.waitFor(() => expect(f.listenerCounts().removed).toBe(1))
    f.triggerTabRemoved(42)
    await rejection.handled

    expect(rejection.caught()).toMatchObject({
      code: 'NO_TARGET_TAB',
      retryable: false,
    })
    expect(rejection.caught()).toBeInstanceOf(ActionFailureError)
  })

  it('chrome.tabs.update rejection -> throws ActionFailureError(NO_TARGET_TAB)', async () => {
    const f = fakeChrome()
    const navigate = navigateHandler(f.chrome)

    await expect(navigate(99, { url: 'https://example.com', wait_for: 'load' }, 5000)).rejects.toMatchObject({
      code: 'NO_TARGET_TAB',
      retryable: false,
    })
  })

  it('iframe load (frameId != 0) is ignored', async () => {
    const f = fakeChrome()
    f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
    const navigate = navigateHandler(f.chrome)

    const promise = navigate(42, { url: 'https://example.com', wait_for: 'load' }, 5000)
    await vi.waitFor(() => expect(f.listenerCounts().completed).toBe(1))
    f.triggerCompleted(42, 99, 'https://iframe.example.com')

    let settled = false
    promise.then(() => { settled = true }, () => { settled = true })
    await Promise.resolve()
    expect(settled).toBe(false)

    f.triggerCompleted(42, 0, 'https://example.com/')
    await expect(promise).resolves.toMatchObject({ ok: true })
  })

  it('handler removes all chrome listeners on success', async () => {
    const f = fakeChrome()
    f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
    const navigate = navigateHandler(f.chrome)

    const promise = navigate(42, { url: 'https://example.com', wait_for: 'load' }, 5000)
    await vi.waitFor(() => expect(f.listenerCounts().completed).toBe(1))
    f.triggerCompleted(42, 0, 'https://example.com/')
    await promise

    expect(f.listenerCounts()).toEqual({ completed: 0, dcl: 0, request: 0, removed: 0 })
  })

  it('handler removes all chrome listeners on failure (timeout)', async () => {
    vi.useFakeTimers()
    try {
      const f = fakeChrome()
      f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
      const navigate = navigateHandler(f.chrome)

      const promise = navigate(42, { url: 'https://example.com', wait_for: 'load' }, 1000)
      const rejection = captureRejection(promise)
      await vi.waitFor(() => expect(f.listenerCounts().completed).toBe(1))
      await vi.advanceTimersByTimeAsync(1000)
      await rejection.handled
      expect(rejection.caught()).toBeInstanceOf(ActionFailureError)

      expect(f.listenerCounts()).toEqual({ completed: 0, dcl: 0, request: 0, removed: 0 })
    } finally {
      vi.useRealTimers()
    }
  })

  it('handler removes all chrome listeners on tab-removed', async () => {
    const f = fakeChrome()
    f.setTabState({ id: 42, url: 'about:blank' } as chrome.tabs.Tab)
    const navigate = navigateHandler(f.chrome)

    const promise = navigate(42, { url: 'https://example.com', wait_for: 'load' }, 5000)
    const rejection = captureRejection(promise)
    await vi.waitFor(() => expect(f.listenerCounts().removed).toBe(1))
    f.triggerTabRemoved(42)
    await rejection.handled
    expect(rejection.caught()).toBeInstanceOf(ActionFailureError)

    expect(f.listenerCounts()).toEqual({ completed: 0, dcl: 0, request: 0, removed: 0 })
  })
})
