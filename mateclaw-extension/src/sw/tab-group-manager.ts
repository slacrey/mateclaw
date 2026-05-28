import type { EdgeMessage } from '../shared/edge-protocol'
import { makeEdgeMessage, EdgeMessageKind } from '../shared/edge-protocol'

const STORAGE_KEY = 'tabGroups'

type TabGroup = {
  mainTabId: number | null
  allTabIds: number[]
}

type TabGroups = Record<string, TabGroup>

/**
 * Maps user subject -> managed Chrome tab ids. Persisted to
 * chrome.storage.local so SW restarts are recoverable.
 *
 * State shape on disk:
 *   { tabGroups: { [subject: string]: { mainTabId: number | null, allTabIds: number[] } } }
 *
 * `mainTabId` is the tab the user explicitly bound as "main" for that
 * subject (via a sidepanel UI action -- out of scope for this task; we just
 * provide setMainTabId() and reads).
 * `allTabIds` is every tab the user has bound to this subject (Phase 3 will
 * use it for multi-tab orchestration; D1 just maintains it).
 */
export class TabGroupManager {
  #groups: TabGroups | null = null
  #mutationQueue = Promise.resolve()

  constructor(
    private readonly chrome: typeof globalThis.chrome,
    private readonly sendUp: (msg: EdgeMessage) => void,
  ) {
    this.chrome.tabs.onRemoved.addListener(this.#onTabRemoved)
    this.chrome.webNavigation.onCompleted.addListener(this.#onPageLoaded)
  }

  /** Rehydrate the in-memory cache from chrome.storage.local. */
  async load(): Promise<void> {
    this.#groups = await this.#readGroups()
  }

  /**
   * Returns the chrome tab id explicitly bound as "main" for this subject,
   * or null if none. Returns null also if the bound tab no longer exists
   * (cleaned up via the onRemoved listener).
   */
  async getMainTabId(subject: string): Promise<number | null> {
    const groups = await this.#loadGroups()
    return groups[subject]?.mainTabId ?? null
  }

  /**
   * Bind tabId as the "main" tab for subject. Also adds it to allTabIds.
   * Persists.
   */
  async setMainTabId(subject: string, tabId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject] ?? { mainTabId: null, allTabIds: [] }
      groups[subject] = {
        mainTabId: tabId,
        allTabIds: uniqueTabIds([...group.allTabIds, tabId]),
      }
    })
  }

  /** Add a tab to subject's group without making it main. */
  async addTab(subject: string, tabId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject] ?? { mainTabId: null, allTabIds: [] }
      groups[subject] = {
        mainTabId: group.mainTabId,
        allTabIds: uniqueTabIds([...group.allTabIds, tabId]),
      }
    })
  }

  /** Remove the binding. Idempotent. */
  async unbind(subject: string): Promise<void> {
    await this.#mutateGroups(groups => {
      groups[subject] = { mainTabId: null, allTabIds: [] }
    })
  }

  #onTabRemoved = async (tabId: number, _info: chrome.tabs.TabRemoveInfo) => {
    let changedSubjectCount = 0

    await this.#mutateGroups(groups => {
      for (const [subject, group] of Object.entries(groups)) {
        const nextTabIds = group.allTabIds.filter(id => id !== tabId)
        const nextMainTabId = group.mainTabId === tabId ? null : group.mainTabId
        const changed =
          nextMainTabId !== group.mainTabId ||
          nextTabIds.length !== group.allTabIds.length

        if (!changed) continue

        groups[subject] = {
          mainTabId: nextMainTabId,
          allTabIds: nextTabIds,
        }
        changedSubjectCount += 1
      }
    })

    for (let i = 0; i < changedSubjectCount; i += 1) {
      this.sendUp(makeEdgeMessage({
        kind: EdgeMessageKind.EventTabClosed,
        payload: { tab_ref: tabId },
      }))
    }
  }

  #onPageLoaded = async (details: chrome.webNavigation.WebNavigationFramedCallbackDetails) => {
    if (details.frameId !== 0) return

    const groups = await this.#loadGroups()
    const isManaged = Object.values(groups).some(group => group.allTabIds.includes(details.tabId))
    if (!isManaged) return

    this.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.EventPageNavigated,
      payload: { tab_ref: details.tabId, url: details.url },
    }))
  }

  async #loadGroups(): Promise<TabGroups> {
    if (this.#groups) return this.#groups
    this.#groups = await this.#readGroups()
    return this.#groups
  }

  async #readGroups(): Promise<TabGroups> {
    const result = await this.chrome.storage.local.get(STORAGE_KEY) as Record<string, unknown>
    return normalizeGroups(result[STORAGE_KEY])
  }

  async #saveGroups(groups: TabGroups): Promise<void> {
    this.#groups = groups
    await this.chrome.storage.local.set({ [STORAGE_KEY]: groups })
  }

  async #mutateGroups(mutator: (groups: TabGroups) => void): Promise<void> {
    const run = async () => {
      const groups = cloneGroups(await this.#loadGroups())
      mutator(groups)
      await this.#saveGroups(groups)
    }

    const next = this.#mutationQueue.then(run, run)
    this.#mutationQueue = next.catch(() => undefined)
    await next
  }
}

function uniqueTabIds(tabIds: number[]): number[] {
  return [...new Set(tabIds)]
}

function cloneGroups(groups: TabGroups): TabGroups {
  return Object.fromEntries(
    Object.entries(groups).map(([subject, group]) => [
      subject,
      { mainTabId: group.mainTabId, allTabIds: [...group.allTabIds] },
    ]),
  )
}

function normalizeGroups(raw: unknown): TabGroups {
  if (!raw || typeof raw !== 'object') return {}

  const groups: TabGroups = {}
  for (const [subject, value] of Object.entries(raw)) {
    if (!value || typeof value !== 'object') continue

    const maybeGroup = value as Partial<TabGroup>
    const mainTabId = typeof maybeGroup.mainTabId === 'number' ? maybeGroup.mainTabId : null
    const allTabIds = Array.isArray(maybeGroup.allTabIds)
      ? uniqueTabIds(maybeGroup.allTabIds.filter((id): id is number => typeof id === 'number'))
      : []

    groups[subject] = { mainTabId, allTabIds }
  }

  return groups
}
