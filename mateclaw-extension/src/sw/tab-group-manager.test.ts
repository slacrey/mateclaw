import { describe, expect, it, vi } from 'vitest'
import type { EdgeMessage } from '../shared/edge-protocol'
import { EdgeMessageKind } from '../shared/edge-protocol'
import { TabGroupManager } from './tab-group-manager'

type TabGroup = { mainTabId: number | null; allTabIds: number[] }
type StorageShape = { tabGroups: Record<string, TabGroup> }

function fakeChrome(storage: StorageShape = { tabGroups: {} }) {
  const onRemovedListeners: Array<(tabId: number, info: any) => void> = []
  const onPageLoadedListeners: Array<(details: any) => void> = []
  const sentUp: EdgeMessage[] = []

  return {
    chrome: {
      storage: {
        local: {
          get: vi.fn(async (key: string) => ({
            [key]: storage[key as keyof StorageShape] ?? {},
          })),
          set: vi.fn(async (patch: Partial<StorageShape>) => Object.assign(storage, patch)),
        },
      },
      tabs: {
        onRemoved: {
          addListener: (fn: any) => onRemovedListeners.push(fn),
        },
      },
      webNavigation: {
        onCompleted: {
          addListener: (fn: any) => onPageLoadedListeners.push(fn),
        },
      },
    } as unknown as typeof globalThis.chrome,
    storage,
    sentUp,
    sendUp: (msg: EdgeMessage) => {
      sentUp.push(msg)
    },
    triggerTabClose: async (tabId: number) => {
      await Promise.all(onRemovedListeners.map(fn => fn(tabId, {})))
    },
    triggerPageLoad: async (tabId: number, url: string, frameId = 0) => {
      await Promise.all(onPageLoadedListeners.map(fn => fn({ tabId, url, frameId })))
    },
  }
}

describe('TabGroupManager', () => {
  it('setMainTabId persists and getMainTabId returns it', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)

    expect(f.storage.tabGroups.alice).toEqual({ mainTabId: 42, allTabIds: [42] })
    expect(await manager.getMainTabId('alice')).toBe(42)
  })

  it('getMainTabId returns null for unknown subject', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    expect(await manager.getMainTabId('alice')).toBeNull()
  })

  it('getMainTabId returns null if the bound tab was later closed', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerTabClose(42)

    expect(await manager.getMainTabId('alice')).toBeNull()
  })

  it('addTab puts tab in allTabIds but does NOT set mainTabId', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.addTab('alice', 42)

    expect(f.storage.tabGroups.alice).toEqual({ mainTabId: null, allTabIds: [42] })
    expect(await manager.getMainTabId('alice')).toBeNull()
  })

  it('tab close emits event.tab.closed envelope with tab_ref=<int>', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerTabClose(42)

    expect(f.sentUp).toHaveLength(1)
    expect(f.sentUp[0]!.kind).toBe(EdgeMessageKind.EventTabClosed)
    expect(f.sentUp[0]!.payload?.tab_ref).toBe(42)
  })

  it('tab close on unmanaged tab does NOT emit envelope', async () => {
    const f = fakeChrome()
    new TabGroupManager(f.chrome, f.sendUp)

    await f.triggerTabClose(99)

    expect(f.sentUp).toHaveLength(0)
  })

  it('page load on managed tab emits event.page.navigated with url', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerPageLoad(42, 'https://x.com')

    expect(f.sentUp).toHaveLength(1)
    expect(f.sentUp[0]!.kind).toBe(EdgeMessageKind.EventPageNavigated)
    expect(f.sentUp[0]!.payload?.tab_ref).toBe(42)
    expect(f.sentUp[0]!.payload?.url).toBe('https://x.com')
  })

  it('page load on unmanaged tab does NOT emit envelope', async () => {
    const f = fakeChrome()
    new TabGroupManager(f.chrome, f.sendUp)

    await f.triggerPageLoad(42, 'https://x.com')

    expect(f.sentUp).toHaveLength(0)
  })

  it('iframe load (frameId != 0) does NOT emit envelope', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerPageLoad(42, 'https://iframe.x.com', 99)

    expect(f.sentUp).toHaveLength(0)
  })

  it('SW restart simulation: load() rehydrates state from chrome.storage.local', async () => {
    const sharedStorage: StorageShape = { tabGroups: {} }
    const first = fakeChrome(sharedStorage)
    const manager1 = new TabGroupManager(first.chrome, first.sendUp)

    await manager1.setMainTabId('alice', 42)

    const second = fakeChrome(sharedStorage)
    const manager2 = new TabGroupManager(second.chrome, second.sendUp)

    await manager2.load()

    expect(await manager2.getMainTabId('alice')).toBe(42)
  })

  it('envelope session_id is "" (P0-1 invariant from Phase 1)', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerPageLoad(42, 'https://x.com')
    await f.triggerTabClose(42)

    expect(f.sentUp).toHaveLength(2)
    expect(f.sentUp.every(msg => msg.session_id === '')).toBe(true)
  })

  it('unbind clears mainTabId and allTabIds for that subject', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.addTab('alice', 43)
    await manager.unbind('alice')

    expect(f.storage.tabGroups.alice).toEqual({ mainTabId: null, allTabIds: [] })
    expect(await manager.getMainTabId('alice')).toBeNull()
  })
})
