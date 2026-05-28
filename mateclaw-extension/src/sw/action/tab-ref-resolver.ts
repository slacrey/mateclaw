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
      return this.deps.tabGroupManager.getMainTabId(this.deps.subject)
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
