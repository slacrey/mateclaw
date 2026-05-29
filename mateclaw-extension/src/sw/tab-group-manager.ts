import type { EdgeMessage } from '../shared/edge-protocol'
import { makeEdgeMessage, EdgeMessageKind } from '../shared/edge-protocol'

const STORAGE_KEY = 'tabGroups'

/**
 * Title + color of the Chrome tab group the agent's tabs sit in. Mirrors the
 * official "Claude in Chrome" extension, which titles its group "Claude" and
 * colors it ORANGE (see assets/mcpPermissions-*.js `createGroup`:
 *   `chrome.tabGroups.update(gid, { title: "Claude", color: ORANGE, collapsed: false })`).
 * MateClaw uses its own brand name; orange reads as "the agent's tabs".
 */
const GROUP_TITLE = 'MateClaw'
/**
 * Chrome's fixed tab-group palette. The installed @types/chrome in this tree
 * doesn't export `tabGroups.ColorEnum`, so we declare the literal union locally
 * (the wire value is just one of these strings).
 */
type TabGroupColor =
  | 'grey' | 'blue' | 'red' | 'yellow' | 'green' | 'pink' | 'purple' | 'cyan' | 'orange'
const GROUP_COLOR: TabGroupColor = 'orange'

type TabGroup = {
  mainTabId: number | null
  allTabIds: number[]
  /**
   * The Chrome `tabGroups` id every tab in `allTabIds` is grouped under, or
   * null if the agent's tabs have not been grouped yet. Lets us reuse one
   * labeled group per subject and clean it up — the official extension tracks
   * the equivalent `chromeGroupId` in its group metadata.
   */
  chromeGroupId: number | null
}

type TabGroups = Record<string, TabGroup>

/**
 * Maps user subject -> managed Chrome tab ids. Persisted to
 * chrome.storage.local so SW restarts are recoverable.
 *
 * State shape on disk:
 *   { tabGroups: { [subject: string]: {
 *       mainTabId: number | null,
 *       allTabIds: number[],
 *       chromeGroupId: number | null
 *   } } }
 *
 * `mainTabId` is the tab the user explicitly bound as "main" for that
 * subject (via a sidepanel UI action -- out of scope for this task; we just
 * provide setMainTabId() and reads).
 * `allTabIds` is every tab the user has bound to this subject (Phase 3 will
 * use it for multi-tab orchestration; D1 just maintains it).
 * `chromeGroupId` is the Chrome tab-group id those tabs visibly sit in. It is
 * managed by joinChromeGroup() and mirrors the official extension's labeled,
 * colored agent group.
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
   * Persists. Preserves any existing chromeGroupId.
   */
  async setMainTabId(subject: string, tabId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject] ?? emptyGroup()
      groups[subject] = {
        mainTabId: tabId,
        allTabIds: uniqueTabIds([...group.allTabIds, tabId]),
        chromeGroupId: group.chromeGroupId,
      }
    })
  }

  /** Add a tab to subject's group without making it main. */
  async addTab(subject: string, tabId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject] ?? emptyGroup()
      groups[subject] = {
        mainTabId: group.mainTabId,
        allTabIds: uniqueTabIds([...group.allTabIds, tabId]),
        chromeGroupId: group.chromeGroupId,
      }
    })
  }

  /**
   * Returns the Chrome tab-group id the subject's tabs sit in, or null if no
   * group has been created yet.
   */
  async getChromeGroupId(subject: string): Promise<number | null> {
    const groups = await this.#loadGroups()
    return groups[subject]?.chromeGroupId ?? null
  }

  /**
   * Make `tabId` join the subject's labeled Chrome tab group, creating the
   * group (titled "MateClaw", orange, expanded) on first use. Mirrors the
   * official extension's findGroupByTab → reuse-or-create flow:
   *
   *   - If we already track a chromeGroupId for this subject, join it:
   *       chrome.tabs.group({ tabIds: [tabId], groupId })
   *   - Otherwise create a fresh group and title/color it:
   *       const gid = await chrome.tabs.group({ tabIds: [tabId] })
   *       await chrome.tabGroups.update(gid, { title, color, collapsed: false })
   *
   * Grouping is purely visual — it never changes the tab id callers resolve.
   * Best-effort: if the chrome.tabGroups API is unavailable or a tracked group
   * was dissolved by the user, we transparently fall back to creating a new
   * one. Failures are swallowed (the agent must still work without grouping).
   *
   * Returns the resulting Chrome group id, or null if grouping failed.
   */
  async joinChromeGroup(subject: string, tabId: number): Promise<number | null> {
    if (!this.chrome.tabs?.group || !this.chrome.tabGroups?.update) {
      // Older Chrome / test fakes without the API. Grouping is optional.
      return null
    }

    const existing = await this.getChromeGroupId(subject)

    // Try to join the already-tracked group first.
    if (existing !== null) {
      try {
        await this.chrome.tabs.group({ tabIds: [tabId], groupId: existing })
        await this.#setChromeGroupId(subject, existing)
        return existing
      } catch {
        // The tracked group was likely closed/dissolved by the user. Fall
        // through and create a fresh one (mirrors the official's
        // adoptOrphanedGroup / recreate behaviour at a reasonable level).
      }
    }

    try {
      const groupId = await this.chrome.tabs.group({ tabIds: [tabId] })
      await this.chrome.tabGroups.update(groupId, {
        title: GROUP_TITLE,
        color: GROUP_COLOR,
        collapsed: false,
      })
      await this.#setChromeGroupId(subject, groupId)
      return groupId
    } catch {
      // Grouping is a visual nicety; never let it break tab provisioning.
      return null
    }
  }

  /** Remove the binding. Idempotent. Drops the tracked chromeGroupId too. */
  async unbind(subject: string): Promise<void> {
    await this.#mutateGroups(groups => {
      groups[subject] = emptyGroup()
    })
  }

  /** Persist the Chrome group id for subject, creating the row if needed. */
  async #setChromeGroupId(subject: string, chromeGroupId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject] ?? emptyGroup()
      groups[subject] = {
        mainTabId: group.mainTabId,
        allTabIds: group.allTabIds,
        chromeGroupId,
      }
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

        // Chrome auto-removes an empty tab group; drop our stale id so the
        // next joinChromeGroup() creates a fresh labeled group rather than
        // trying to add to a group that no longer exists.
        const nextChromeGroupId = nextTabIds.length === 0 ? null : group.chromeGroupId

        groups[subject] = {
          mainTabId: nextMainTabId,
          allTabIds: nextTabIds,
          chromeGroupId: nextChromeGroupId,
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

function emptyGroup(): TabGroup {
  return { mainTabId: null, allTabIds: [], chromeGroupId: null }
}

function uniqueTabIds(tabIds: number[]): number[] {
  return [...new Set(tabIds)]
}

function cloneGroups(groups: TabGroups): TabGroups {
  return Object.fromEntries(
    Object.entries(groups).map(([subject, group]) => [
      subject,
      {
        mainTabId: group.mainTabId,
        allTabIds: [...group.allTabIds],
        chromeGroupId: group.chromeGroupId,
      },
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
    // Tolerate state persisted before chromeGroupId existed (defaults null).
    const chromeGroupId = typeof maybeGroup.chromeGroupId === 'number'
      ? maybeGroup.chromeGroupId
      : null

    groups[subject] = { mainTabId, allTabIds, chromeGroupId }
  }

  return groups
}
