import type { TabRef } from './types'
import type { TabGroupManager } from '../tab-group-manager'

export interface TabRefResolverDeps {
  tabGroupManager: TabGroupManager
  /** Defaults to globalThis.chrome — injectable for tests. */
  chrome?: typeof globalThis.chrome
  /** Identifies the current user — used by getMainTabId. Phase 2 hardcoded; Phase 4 from auth. */
  subject: string
}

/**
 * Resolves a TabRef wire-form value to a concrete Chrome tab id.
 *
 *   "main"   → TabGroupManager.getMainTabId(subject)
 *   "active" → chrome.tabs.query({ active: true, lastFocusedWindow: true })[0].id
 *   <int>    → that integer literally
 *
 * Returns null if resolution fails (no main tab, no active tab, or wrong type).
 * Callers should map null to ActionResult.Failure(code='NO_TARGET_TAB').
 */
export class TabRefResolver {
  constructor(private readonly deps: TabRefResolverDeps) {}

  async resolve(tabRef: TabRef): Promise<number | null> {
    if (typeof tabRef === 'number') {
      return tabRef
    }

    if (tabRef === 'main') {
      const bound = await this.deps.tabGroupManager.getMainTabId(this.deps.subject)
      if (bound !== null) return bound
      // No main tab bound yet. Provision a dedicated agent tab rather than
      // failing (NO_TARGET_TAB) or hijacking whatever the user is looking at.
      // This is what makes "open a page in my browser" work on first use: the
      // agent gets its own visible tab, and the binding persists so follow-up
      // observe/click/type actions resolve "main" to the same tab.
      const chrome = this.deps.chrome ?? globalThis.chrome
      let created: chrome.tabs.Tab
      try {
        created = await chrome.tabs.create({ url: 'about:blank', active: true })
      } catch {
        return null
      }
      if (typeof created.id !== 'number') return null
      await this.deps.tabGroupManager.setMainTabId(this.deps.subject, created.id)
      // Drop the new agent tab into a labeled, colored Chrome tab group so the
      // user can see at a glance which tabs the agent owns (mirrors the
      // official "Claude in Chrome" group). Purely visual — the resolved tab
      // id is unchanged. Best-effort: joinChromeGroup swallows its own errors
      // and the manager returns null when the API is unavailable.
      await this.deps.tabGroupManager.joinChromeGroup(this.deps.subject, created.id)
      return created.id
    }

    if (tabRef === 'active') {
      const chrome = this.deps.chrome ?? globalThis.chrome
      const tabs = await chrome.tabs.query({
        active: true,
        lastFocusedWindow: true,
      })
      const first = tabs[0]
      if (!first || typeof first.id !== 'number') return null
      return first.id
    }

    // Future-proof: an unrecognized string falls through here. Treat as a
    // resolution miss so the router can surface NO_TARGET_TAB.
    return null
  }
}
