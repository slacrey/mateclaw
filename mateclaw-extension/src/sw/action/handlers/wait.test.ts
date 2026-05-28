import { afterEach, describe, expect, it, vi } from 'vitest'
import { ActionFailureError } from '../ActionExecutor'
import { waitHandler } from './wait'

type NavigationDetails = Pick<
  chrome.webNavigation.WebNavigationFramedCallbackDetails,
  'tabId' | 'frameId'
>
type NavigationListener = (details: chrome.webNavigation.WebNavigationFramedCallbackDetails) => void

function fakeChrome() {
  const completed = new Set<NavigationListener>()
  const domContentLoaded = new Set<NavigationListener>()

  return {
    chrome: {
      webNavigation: {
        onCompleted: {
          addListener: vi.fn((listener: NavigationListener) => completed.add(listener)),
          removeListener: vi.fn((listener: NavigationListener) => completed.delete(listener)),
        },
        onDOMContentLoaded: {
          addListener: vi.fn((listener: NavigationListener) => domContentLoaded.add(listener)),
          removeListener: vi.fn((listener: NavigationListener) => domContentLoaded.delete(listener)),
        },
      },
    } as unknown as typeof globalThis.chrome,
    fireCompleted(details: NavigationDetails) {
      for (const listener of completed) {
        listener(details as chrome.webNavigation.WebNavigationFramedCallbackDetails)
      }
    },
    fireDomContentLoaded(details: NavigationDetails) {
      for (const listener of domContentLoaded) {
        listener(details as chrome.webNavigation.WebNavigationFramedCallbackDetails)
      }
    },
    completedCount() {
      return completed.size
    },
    domContentLoadedCount() {
      return domContentLoaded.size
    },
  }
}

function expectFailure(promise: Promise<unknown>, code: string) {
  return expect(promise).rejects.toMatchObject({
    name: 'ActionFailureError',
    code,
  })
}

describe('wait handler', () => {
  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('strategy=time + duration_ms=500 returns Success(waited_ms ~500)', async () => {
    vi.useFakeTimers()
    let now = 0
    const sleep = vi.fn(async (ms: number) => {
      await vi.advanceTimersByTimeAsync(ms)
      now += ms
    })
    const handler = waitHandler({ clock: () => now, sleep })

    const result = await handler(42, { strategy: 'time', duration_ms: 500 }, 1000)

    expect(sleep).toHaveBeenCalledExactlyOnceWith(500)
    expect(result).toMatchObject({
      ok: true,
      payload: { waited_ms: 500 },
    })
  })

  it('strategy=time without duration_ms throws ActionFailureError(VALIDATION)', async () => {
    const sleep = vi.fn(async (_ms: number) => {})
    const handler = waitHandler({ sleep })

    await expectFailure(handler(42, { strategy: 'time' }, 1000), 'VALIDATION')
    expect(sleep).not.toHaveBeenCalled()
  })

  it('strategy=time with duration_ms exceeding deadline_ms throws TIMEOUT_PAGE_LOAD', async () => {
    const sleep = vi.fn(async (_ms: number) => {})
    const handler = waitHandler({ sleep })

    await expectFailure(
      handler(42, { strategy: 'time', duration_ms: 10_000 }, 5000),
      'TIMEOUT_PAGE_LOAD',
    )
    expect(sleep).not.toHaveBeenCalled()
  })

  it('strategy=load_state load resolves when webNavigation.onCompleted fires for tabId+frameId=0', async () => {
    vi.useFakeTimers()
    const f = fakeChrome()
    const handler = waitHandler({ chrome: f.chrome })

    const resultPromise = handler(42, { strategy: 'load_state', load_state: 'load' }, 5000)
    expect(f.completedCount()).toBe(1)

    f.fireCompleted({ tabId: 7, frameId: 0 })
    expect(f.completedCount()).toBe(1)

    f.fireCompleted({ tabId: 42, frameId: 0 })
    await expect(resultPromise).resolves.toMatchObject({
      ok: true,
      payload: { waited_ms: 0 },
    })
    expect(f.completedCount()).toBe(0)
  })

  it('strategy=load_state domcontentloaded resolves on onDOMContentLoaded', async () => {
    vi.useFakeTimers()
    const f = fakeChrome()
    const handler = waitHandler({ chrome: f.chrome })

    const resultPromise = handler(42, {
      strategy: 'load_state',
      load_state: 'domcontentloaded',
    }, 5000)
    expect(f.domContentLoadedCount()).toBe(1)

    f.fireDomContentLoaded({ tabId: 42, frameId: 0 })
    await expect(resultPromise).resolves.toMatchObject({
      ok: true,
      payload: { waited_ms: 0 },
    })
    expect(f.domContentLoadedCount()).toBe(0)
  })

  it('strategy=load_state never fires timeout at deadline_ms throws TIMEOUT_PAGE_LOAD', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    const f = fakeChrome()
    const handler = waitHandler({ chrome: f.chrome })

    const resultPromise = handler(42, { strategy: 'load_state', load_state: 'load' }, 5000)
    const rejection = expectFailure(resultPromise, 'TIMEOUT_PAGE_LOAD')
    await vi.advanceTimersByTimeAsync(5000)

    await rejection
    expect(f.completedCount()).toBe(0)
  })

  it('strategy=load_state iframe (frameId != 0) does NOT resolve', async () => {
    vi.useFakeTimers()
    const f = fakeChrome()
    const handler = waitHandler({ chrome: f.chrome })
    let settled = false

    const resultPromise = handler(42, { strategy: 'load_state', load_state: 'load' }, 5000)
      .finally(() => {
        settled = true
      })

    f.fireCompleted({ tabId: 42, frameId: 1 })
    await Promise.resolve()
    expect(settled).toBe(false)
    expect(f.completedCount()).toBe(1)

    f.fireCompleted({ tabId: 42, frameId: 0 })
    await expect(resultPromise).resolves.toMatchObject({ ok: true })
    expect(f.completedCount()).toBe(0)
  })

  it('strategy=network_idle fallback sleeps idle_threshold_ms then returns', async () => {
    let now = 100
    const sleep = vi.fn(async (ms: number) => {
      now += ms
    })
    const handler = waitHandler({ clock: () => now, sleep })

    const result = await handler(42, { strategy: 'network_idle', idle_threshold_ms: 275 }, 1000)

    expect(sleep).toHaveBeenCalledExactlyOnceWith(275)
    expect(result).toMatchObject({
      ok: true,
      payload: { waited_ms: 275 },
    })
  })

  it('strategy=network_idle default threshold = 500ms when not specified', async () => {
    let now = 0
    const sleep = vi.fn(async (ms: number) => {
      now += ms
    })
    const handler = waitHandler({ clock: () => now, sleep })

    const result = await handler(42, { strategy: 'network_idle' }, 1000)

    expect(sleep).toHaveBeenCalledExactlyOnceWith(500)
    expect(result).toMatchObject({
      ok: true,
      payload: { waited_ms: 500 },
    })
  })

  it('removes all chrome listeners in finally on success and on failure', async () => {
    vi.useFakeTimers()

    const successChrome = fakeChrome()
    const successHandler = waitHandler({ chrome: successChrome.chrome })
    const successPromise = successHandler(42, {
      strategy: 'load_state',
      load_state: 'domcontentloaded',
    }, 5000)

    successChrome.fireDomContentLoaded({ tabId: 42, frameId: 0 })
    await expect(successPromise).resolves.toMatchObject({ ok: true })
    expect(successChrome.domContentLoadedCount()).toBe(0)
    expect(successChrome.chrome.webNavigation.onDOMContentLoaded.removeListener).toHaveBeenCalledOnce()

    const failureChrome = fakeChrome()
    const failureHandler = waitHandler({ chrome: failureChrome.chrome })
    const failurePromise = failureHandler(42, {
      strategy: 'load_state',
      load_state: 'load',
    }, 5000)
    const rejection = expectFailure(failurePromise, 'TIMEOUT_PAGE_LOAD')

    await vi.advanceTimersByTimeAsync(5000)
    await rejection
    expect(failureChrome.completedCount()).toBe(0)
    expect(failureChrome.chrome.webNavigation.onCompleted.removeListener).toHaveBeenCalledOnce()
  })

  it('waited_ms accurately reflects wall-clock from start to completion', async () => {
    let now = 1000
    const sleep = vi.fn(async (_ms: number) => {
      now = 1234
    })
    const handler = waitHandler({ clock: () => now, sleep })

    const result = await handler(42, { strategy: 'time', duration_ms: 50 }, 5000)

    expect(result).toMatchObject({
      ok: true,
      elapsed_ms: 234,
      payload: { waited_ms: 234 },
    })
  })

  it('invalid strategy value throws ActionFailureError(VALIDATION)', async () => {
    const sleep = vi.fn(async (_ms: number) => {})
    const handler = waitHandler({ sleep })

    await expectFailure(
      handler(42, { strategy: 'invalid' } as any, 5000),
      'VALIDATION',
    )
    expect(sleep).not.toHaveBeenCalled()
  })

  it('throws ActionFailureError instances for typed failures', async () => {
    const handler = waitHandler({ sleep: vi.fn(async (_ms: number) => {}) })

    await expect(handler(42, { strategy: 'time' }, 5000)).rejects.toBeInstanceOf(ActionFailureError)
  })
})
