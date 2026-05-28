import { describe, expect, it, vi } from 'vitest'
import { TabRefResolver } from './tab-ref-resolver'
import type { TabGroupManager } from '../tab-group-manager'

/**
 * Build a minimal fake TabGroupManager that only implements the surface
 * area the resolver depends on (getMainTabId).
 */
function fakeTabGroupManager(mainTabId: number | null): TabGroupManager {
  return {
    getMainTabId: vi.fn(async () => mainTabId),
  } as unknown as TabGroupManager
}

/**
 * Build a minimal fake chrome object exposing only chrome.tabs.query.
 * Chrome's real API is callback-based, but the resolver should use the
 * Promise overload (Chrome 88+, MV3-friendly) for clean async/await.
 */
function fakeChrome(activeTabId: number | null): typeof globalThis.chrome {
  const result = activeTabId == null ? [] : [{ id: activeTabId }]
  return {
    tabs: {
      query: vi.fn(async () => result),
    },
  } as unknown as typeof globalThis.chrome
}

describe('TabRefResolver', () => {
  // -----------------------------------------------------------------
  // "main" resolution
  // -----------------------------------------------------------------

  it('resolves "main" via TabGroupManager.getMainTabId(subject)', async () => {
    const tgm = fakeTabGroupManager(42)
    const resolver = new TabRefResolver({
      tabGroupManager: tgm,
      chrome: fakeChrome(null),
      subject: 'alice',
    })

    const tabId = await resolver.resolve('main')

    expect(tabId).toBe(42)
    expect(tgm.getMainTabId).toHaveBeenCalledExactlyOnceWith('alice')
  })

  it('"main" with no bound tab returns null', async () => {
    const tgm = fakeTabGroupManager(null)
    const resolver = new TabRefResolver({
      tabGroupManager: tgm,
      chrome: fakeChrome(99),  // active tab exists but should NOT be used
      subject: 'alice',
    })

    const tabId = await resolver.resolve('main')

    expect(tabId).toBeNull()
  })

  // -----------------------------------------------------------------
  // "active" resolution
  // -----------------------------------------------------------------

  it('resolves "active" via chrome.tabs.query({active:true, lastFocusedWindow:true})', async () => {
    const chrome = fakeChrome(7)
    const resolver = new TabRefResolver({
      tabGroupManager: fakeTabGroupManager(42),  // not used
      chrome,
      subject: 'alice',
    })

    const tabId = await resolver.resolve('active')

    expect(tabId).toBe(7)
    expect(chrome.tabs.query).toHaveBeenCalledExactlyOnceWith({
      active: true,
      lastFocusedWindow: true,
    })
  })

  it('"active" with no focused window returns null', async () => {
    const resolver = new TabRefResolver({
      tabGroupManager: fakeTabGroupManager(42),
      chrome: fakeChrome(null),
      subject: 'alice',
    })

    const tabId = await resolver.resolve('active')

    expect(tabId).toBeNull()
  })

  it('"active" returns null when chrome.tabs.query returns an entry missing an id', async () => {
    const chrome = {
      tabs: {
        // chrome.tabs.Tab.id can legitimately be undefined for dev-tools / non-page tabs
        query: vi.fn(async () => [{ id: undefined }]),
      },
    } as unknown as typeof globalThis.chrome
    const resolver = new TabRefResolver({
      tabGroupManager: fakeTabGroupManager(42),
      chrome,
      subject: 'alice',
    })

    const tabId = await resolver.resolve('active')

    expect(tabId).toBeNull()
  })

  // -----------------------------------------------------------------
  // integer pass-through
  // -----------------------------------------------------------------

  it('integer tab_ref is returned verbatim (no lookups performed)', async () => {
    const tgm = fakeTabGroupManager(42)
    const chrome = fakeChrome(99)
    const resolver = new TabRefResolver({ tabGroupManager: tgm, chrome, subject: 'alice' })

    const tabId = await resolver.resolve(123)

    expect(tabId).toBe(123)
    expect(tgm.getMainTabId).not.toHaveBeenCalled()
    expect(chrome.tabs.query).not.toHaveBeenCalled()
  })

  it('integer tab_ref 0 is returned verbatim (no zero-truthy bug)', async () => {
    // Chrome tabIds start at 1 in practice, but the resolver must not
    // accidentally treat 0 as falsy and return null.
    const resolver = new TabRefResolver({
      tabGroupManager: fakeTabGroupManager(42),
      chrome: fakeChrome(99),
      subject: 'alice',
    })

    const tabId = await resolver.resolve(0)

    expect(tabId).toBe(0)
  })

  // -----------------------------------------------------------------
  // defaults
  // -----------------------------------------------------------------

  it('uses global chrome when deps.chrome is omitted', async () => {
    const tgm = fakeTabGroupManager(42)
    // Inject globalThis.chrome for the duration of this test
    const originalChrome = (globalThis as { chrome?: unknown }).chrome
    const fakeGlobalChrome = fakeChrome(55)
    ;(globalThis as { chrome?: unknown }).chrome = fakeGlobalChrome

    try {
      const resolver = new TabRefResolver({
        tabGroupManager: tgm,
        subject: 'alice',
      })

      const tabId = await resolver.resolve('active')

      expect(tabId).toBe(55)
    } finally {
      ;(globalThis as { chrome?: unknown }).chrome = originalChrome
    }
  })
})
