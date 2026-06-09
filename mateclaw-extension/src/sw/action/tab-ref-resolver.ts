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
 *   "active" → active tab inside the subject's managed MateClaw tab group
 *   <int>    → that integer literally
 *
 * Returns null if resolution fails (no main tab, no managed active tab, or wrong type).
 * Callers should map null to ActionResult.Failure(code='NO_TARGET_TAB').
 */
export class TabRefResolver {
  constructor(private readonly deps: TabRefResolverDeps) {}

  async resolve(
    tabRef: TabRef,
    opts: { createIfMissing?: boolean } = {},
  ): Promise<number | null> {
    if (typeof tabRef === 'number') {
      return tabRef
    }

    if (tabRef === 'main') {
      const bound = await this.deps.tabGroupManager.getMainTabId(this.deps.subject)
      if (bound !== null) return bound
      // Only navigate provisions a tab; observe/click/type must reuse the
      // existing one. Scripting a fresh about:blank yields an empty a11y tree
      // (the intermittent "tree is empty" the user hit when the agent's tab had
      // been closed). Missing main + !createIfMissing → null → NO_TARGET_TAB so
      // the orchestrator re-navigates instead of reading a blank page.
      if (!opts.createIfMissing) return null
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
      try {
        const resolver = this.deps.tabGroupManager.getActiveTabId
        if (typeof resolver !== 'function') return null
        return await resolver.call(this.deps.tabGroupManager, this.deps.subject)
      } catch {
        return null
      }
    }

    // Future-proof: an unrecognized string falls through here. Treat as a
    // resolution miss so the router can surface NO_TARGET_TAB.
    return null
  }
}
