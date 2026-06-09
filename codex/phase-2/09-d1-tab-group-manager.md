# Codex Task 09 — Phase 2 D1：TabGroupManager + tab lifecycle 事件发射

> 你（Codex）要为 MateClaw 浏览器扩展实现 **TabGroupManager**：
> 把"用户 subject → 主 tab id"的映射持久化到 chrome.storage.local，
> 监听 tab/页面生命周期事件，把它们映射成 `event.tab.closed` /
> `event.page.navigated` envelope 通过 Native Bridge 上行。

## 项目背景

- 协议 v1.1 引入 `tab_ref: "main" | "active" | <int>`（见 `docs/specs/edge-protocol.md` §TabRef）。
- SW 收到 `action.execute` 带 `tab_ref="main"` 时，必须能在 O(1) 内解析为 chrome 整数 tab id。
- 这个映射不能丢失 SW 重启（MV3 SW 会被随时杀掉），所以**持久化到 chrome.storage.local**。
- 同时 SW 必须监听 tab 关闭和页面导航 — 发上行事件让 Control Plane 的 PageSnapshotService（Wave 4 F5 任务）能正确把缓存的 a11y 快照标 STALE。

阅读这几个文件先理清上下文：
- `mateclaw-extension/src/shared/edge-protocol.ts` — `EdgeMessageKind.EventTabClosed` / `EdgeMessageKind.EventPageNavigated` 已存在
- `mateclaw-extension/src/sw/native-bridge.ts` — `sendToNative(msg: EdgeMessage)` API
- `docs/specs/edge-protocol.md` §"event.tab.closed" / §"event.page.navigated"

## 你要交付的 2 个文件

放在 `mateclaw-extension/src/sw/`。

### 1. `tab-group-manager.ts`

```typescript
import { EdgeMessage, makeEdgeMessage, EdgeMessageKind } from '../shared/edge-protocol'

const STORAGE_KEY = 'tabGroups'

/**
 * Maps user subject → managed Chrome tab ids. Persisted to
 * chrome.storage.local so SW restarts are recoverable.
 *
 * State shape on disk:
 *   { tabGroups: { [subject: string]: { mainTabId: number | null, allTabIds: number[] } } }
 *
 * `mainTabId` is the tab the user explicitly bound as "main" for that
 * subject (via a sidepanel UI action — out of scope for this task; we just
 * provide setMainTabId() and reads).
 * `allTabIds` is every tab the user has bound to this subject (Phase 3 will
 * use it for multi-tab orchestration; D1 just maintains it).
 */
export class TabGroupManager {
  constructor(
    private readonly chrome: typeof globalThis.chrome,
    private readonly sendUp: (msg: EdgeMessage) => void,
  ) {
    this.chrome.tabs.onRemoved.addListener(this.#onTabRemoved)
    this.chrome.webNavigation.onCompleted.addListener(this.#onPageLoaded)
  }

  /**
   * Returns the chrome tab id explicitly bound as "main" for this subject,
   * or null if none. Returns null also if the bound tab no longer exists
   * (cleaned up via the onRemoved listener).
   */
  async getMainTabId(subject: string): Promise<number | null>

  /**
   * Bind tabId as the "main" tab for subject. Also adds it to allTabIds.
   * Persists.
   */
  async setMainTabId(subject: string, tabId: number): Promise<void>

  /** Add a tab to subject's group without making it main. */
  async addTab(subject: string, tabId: number): Promise<void>

  /** Remove the binding. Idempotent. */
  async unbind(subject: string): Promise<void>

  #onTabRemoved = async (tabId: number, _info: chrome.tabs.TabRemoveInfo) => {
    // 1. Update state — remove tabId from every subject's allTabIds, clear
    //    mainTabId if it was this one.
    // 2. For each subject whose group changed, emit ONE event.tab.closed
    //    envelope: { tab_ref: tabId }.
  }

  #onPageLoaded = async (details: chrome.webNavigation.WebNavigationFramedCallbackDetails) => {
    // Filter: only top-frame loads (details.frameId === 0).
    // Only emit if the tab is in some subject's allTabIds (we don't want to
    // flood the bridge with every page load on every tab).
    // Payload: { tab_ref: details.tabId, url: details.url }.
  }
}
```

### 2. `tab-group-manager.test.ts`

Mock chrome.storage.local + chrome.tabs.onRemoved + chrome.webNavigation.onCompleted. Pattern:

```typescript
function fakeChrome() {
  const storage = { tabGroups: {} as Record<string, { mainTabId: number | null; allTabIds: number[] }> }
  const onRemovedListeners: Array<(tabId: number, info: any) => void> = []
  const onPageLoadedListeners: Array<(details: any) => void> = []

  return {
    chrome: {
      storage: {
        local: {
          get: vi.fn(async (key: string) => ({ [key]: storage[key as keyof typeof storage] ?? {} })),
          set: vi.fn(async (patch: any) => Object.assign(storage, patch)),
        },
      },
      tabs: { onRemoved: { addListener: (fn: any) => onRemovedListeners.push(fn) } },
      webNavigation: { onCompleted: { addListener: (fn: any) => onPageLoadedListeners.push(fn) } },
    } as unknown as typeof globalThis.chrome,
    sentUp: [] as EdgeMessage[],
    sendUp: (msg: EdgeMessage) => { /* push to sentUp */ },
    triggerTabClose: (tabId: number) => onRemovedListeners.forEach(fn => fn(tabId, {})),
    triggerPageLoad: (tabId: number, url: string, frameId = 0) =>
      onPageLoadedListeners.forEach(fn => fn({ tabId, url, frameId })),
  }
}
```

Required test cases:

```typescript
describe('TabGroupManager', () => {
  it('setMainTabId persists and getMainTabId returns it', async () => {})

  it('getMainTabId returns null for unknown subject', async () => {})

  it('getMainTabId returns null if the bound tab was later closed', async () => {
    // setMainTabId('alice', 42); triggerTabClose(42); expect getMainTabId('alice') === null
  })

  it('addTab puts tab in allTabIds but does NOT set mainTabId', async () => {})

  it('tab close emits event.tab.closed envelope with tab_ref=<int>', async () => {
    // setMainTabId('alice', 42); triggerTabClose(42)
    // expect sentUp[0].kind === 'event.tab.closed' && payload.tab_ref === 42
  })

  it('tab close on unmanaged tab does NOT emit envelope', async () => {
    // no setMainTabId calls; triggerTabClose(99); expect sentUp.length === 0
  })

  it('page load on managed tab emits event.page.navigated with url', async () => {
    // setMainTabId('alice', 42); triggerPageLoad(42, 'https://x.com')
    // expect sentUp[0].kind === 'event.page.navigated' && payload.url === 'https://x.com'
  })

  it('page load on unmanaged tab does NOT emit envelope', async () => {})

  it('iframe load (frameId != 0) does NOT emit envelope', async () => {
    // triggerPageLoad(42, 'https://iframe.x.com', /*frameId=*/ 99)
    // expect sentUp.length === 0
  })

  it('SW restart simulation: load() rehydrates state from chrome.storage.local', async () => {
    // Construct TabGroupManager #1; setMainTabId('alice', 42)
    // Construct TabGroupManager #2 sharing the same storage; getMainTabId('alice') === 42
  })

  it('envelope session_id is "" (P0-1 invariant from Phase 1)', async () => {
    // Verify every sentUp message has session_id === '' — the Native Host stamps it.
  })

  it('unbind clears mainTabId and allTabIds for that subject', async () => {})
})
```

### 测试运行

```
cd mateclaw-extension && pnpm test --run tab-group-manager
```

## TDD 步骤

1. 先写完整 `tab-group-manager.test.ts`（11 个测试，全 fail/no-module）。
2. `pnpm test --run tab-group-manager` → `Cannot find module './tab-group-manager'`。
3. 实现 `tab-group-manager.ts`。
4. `pnpm test --run tab-group-manager` → 全绿。
5. Commit.

## Commit message template

```
feat(extension): TabGroupManager + tab/page lifecycle event emitters (D1)

- subject → mainTabId + allTabIds map, persisted to chrome.storage.local
- getMainTabId() / setMainTabId() / addTab() / unbind() API
- chrome.tabs.onRemoved → event.tab.closed envelope to bridge
- chrome.webNavigation.onCompleted (top frame only) → event.page.navigated
- 11 tab-group-manager.test cases incl. SW-restart rehydrate + P0-1 invariant

Phase 2 Wave 1 — task D1.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要实现 sidepanel UI 让用户绑定 tab — Phase 3 工作；这里只暴露 `setMainTabId(subject, tabId)` 程序化 API。
- 不要监听 `chrome.tabs.onUpdated`（标题变更等）— 性能噪声，会让 bridge 上行洪水。只监听 onRemoved + onCompleted。
- 不要改 `src/shared/edge-protocol.ts` — Wave 0 已固定。
- `manifest.json` 里的 `webNavigation` permission 由 C6 (Sub-agent B) 同步加上，你别动 manifest。
