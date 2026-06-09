# Codex Task 20 — Phase 3 T3.2-protocol：screenshot.capture.* wire kinds

> 你（Codex）要把"截图协议"加进 Edge 协议 v1.2：
> - **`screenshot.capture.request`** (CP → NH → Ext): 请求当前 tab 的截图
> - **`screenshot.capture.response`** (Ext → NH → CP): 返回 base64 PNG
>
> 这是 T3.2 VisionEngine 在 Wave 3-A2 需要的输入通道。

## 项目背景

阅读这些文件先理清上下文：
- `docs/specs/edge-protocol.md` —— canonical v1.1 spec (Phase 2 P1 加了 13 个 kind)
- `mateclaw-server/src/main/java/vip/mate/browser/edge/protocol/EdgeMessageKind.java`
- `mateclaw-browser-bridge/src/internal/edgeproto/edgeproto.ts`
- `mateclaw-extension/src/shared/edge-protocol.ts`
- 测试: 三个 runtime 现有的 protocol test 文件，分别加新 case

参考 Phase 2 Wave 0 的 P1 task —— **同样的 pattern**：跨 3 个 runtime 同步新 kind。

## 你要交付的 5 类改动

### 1. Java 侧 — `EdgeMessageKind.java`

在 `// v1.1 — accessibility tree snapshot` 段之后追加：

```java
// -----------------------------------------------------------------
// Protocol v1.2 — screenshot capture (Phase 3 T3.2)
//
// CP → NH → Ext: screenshot.capture.request
// Ext → NH → CP: screenshot.capture.response
// Response carries base64-encoded PNG; large payloads (>500 KB at the
// 1MB NM cap) should fail Failure(SCREENSHOT_TOO_LARGE) rather than
// truncate.
// -----------------------------------------------------------------
SCREENSHOT_CAPTURE_REQUEST("screenshot.capture.request"),
SCREENSHOT_CAPTURE_RESPONSE("screenshot.capture.response"),
```

更新 `EdgeMessageTest.java` 加 round-trip cases for both new kinds.

### 2. NH bridge 侧 — `mateclaw-browser-bridge/src/internal/edgeproto/edgeproto.ts`

在 `Kind` 对象的 v1.1 section 后追加：

```typescript
// v1.2 — screenshot capture (Phase 3 T3.2)
ScreenshotCaptureRequest: 'screenshot.capture.request',
ScreenshotCaptureResponse: 'screenshot.capture.response',
```

更新 `edgeproto.test.ts` 加 round-trip cases for both kinds.

### 3. Extension 侧 — `mateclaw-extension/src/shared/edge-protocol.ts`

同样镜像 v1.2 entries 到 `EdgeMessageKind` const，更新 `edge-protocol.test.ts`。

### 4. Spec doc — `docs/specs/edge-protocol.md`

更新 spec header 到 v1.2，在 "v1.1 additions" 表后追加 "v1.2 additions" section:

| kind | direction | summary |
|---|---|---|
| `screenshot.capture.request` | CP → NH → Ext | request a base64 PNG of the current tab |
| `screenshot.capture.response` | Ext → NH → CP | base64 PNG payload + viewport metadata |

加 payload shape:

```json
// screenshot.capture.request
{
  "tab_ref": "main",
  "format": "png",                  // future: webp / jpeg with quality
  "quality": 90,                    // ignored for png; spec for future jpeg
  "scale_factor": 1                 // 1 = native pixel; >1 = downscale for vision LLM token budget
}

// screenshot.capture.response (success)
{
  "snapshot_id": "shot-uuid",
  "captured_at_ms": 1730000000123,
  "tab_ref": 42,                    // resolved tab id echoed
  "format": "png",
  "data_base64": "iVBORw0KGgoAAAANS...",  // ≤500 KB encoded; SW must reject larger
  "viewport": { "w": 1280, "h": 800 },
  "actual_dimensions": { "w": 1280, "h": 800 }  // post-scale_factor
}

// screenshot.capture.response (failure shape — same envelope, different keys)
{
  "snapshot_id": "shot-uuid",
  "captured_at_ms": ...,
  "tab_ref": -1,
  "error": { "code": "NO_TARGET_TAB" | "SCREENSHOT_TOO_LARGE" | "PERMISSION_DENIED",
             "message": "..." }
}
```

错误码：
- `NO_TARGET_TAB` — `tab_ref` 解析失败
- `SCREENSHOT_TOO_LARGE` — base64 大于 500 KB（NM 1 MB 上限留一半余地）
- `PERMISSION_DENIED` — `chrome.tabs.captureVisibleTab` 抛 `'Failed to capture tab'`（页面是 chrome:// 等受限页）

### 5. Extension SW — 新文件 `mateclaw-extension/src/sw/screenshot-capture-handler.ts`

参考 `mateclaw-extension/src/sw/snapshot-request-handler.ts` (B11) 的结构。

```typescript
import { EdgeMessageKind, makeEdgeMessage, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'

const MAX_BASE64_LENGTH = 500_000   // ~500 KB; NM frame limit is 1 MB

export interface ScreenshotCaptureHandlerDeps {
  resolver: TabRefResolver
  chrome?: typeof globalThis.chrome
  sendUp: (msg: EdgeMessage) => void
  uuid?: () => string
  clock?: () => number
}

export class ScreenshotCaptureHandler {
  constructor(private readonly deps: ScreenshotCaptureHandlerDeps) {}

  async handle(msg: EdgeMessage): Promise<void> {
    if (msg.kind !== EdgeMessageKind.ScreenshotCaptureRequest) return
    const payload = msg.payload ?? {}
    const tabRef = parseTabRef(payload.tab_ref)
    let tabId: number | null = null
    if (tabRef !== null) {
      tabId = await this.deps.resolver.resolve(tabRef)
    }
    const snapshotId = (this.deps.uuid ?? crypto.randomUUID.bind(crypto))()
    const capturedAt = (this.deps.clock ?? Date.now)()

    if (tabId === null) {
      this.respondError(msg, snapshotId, capturedAt, 'NO_TARGET_TAB', `tab_ref ${String(payload.tab_ref)} not resolved`)
      return
    }
    try {
      // chrome.tabs.captureVisibleTab is per-window; the resolved tabId
      // must be in the active window. Phase-3-scope is single-window.
      const dataUrl = await this.chrome().tabs.captureVisibleTab(/* windowId */ -1 as unknown as number, {
        format: 'png',
      })
      // Strip the "data:image/png;base64," prefix.
      const base64 = (dataUrl as string).split(',')[1] ?? ''
      if (base64.length > MAX_BASE64_LENGTH) {
        this.respondError(msg, snapshotId, capturedAt, 'SCREENSHOT_TOO_LARGE',
          `payload ${base64.length} bytes exceeds ${MAX_BASE64_LENGTH}`)
        return
      }
      const viewport = await this.getViewport(tabId)
      this.deps.sendUp(makeEdgeMessage({
        kind: EdgeMessageKind.ScreenshotCaptureResponse,
        traceId: msg.trace_id,
        inReplyTo: msg.msg_id,
        payload: {
          snapshot_id: snapshotId,
          captured_at_ms: capturedAt,
          tab_ref: tabId,
          format: 'png',
          data_base64: base64,
          viewport,
          actual_dimensions: viewport,
        },
      }))
    } catch (err) {
      const code = (err as Error).message?.includes('chrome://') ? 'PERMISSION_DENIED' : 'PERMISSION_DENIED'
      this.respondError(msg, snapshotId, capturedAt, code, (err as Error).message ?? String(err))
    }
  }

  private async getViewport(tabId: number): Promise<{ w: number; h: number }> {
    // Best-effort; query the tab's window for dimensions.
    const tab = await this.chrome().tabs.get(tabId)
    return { w: tab.width ?? 1280, h: tab.height ?? 800 }
  }

  private respondError(req: EdgeMessage, snapshotId: string, capturedAt: number,
                       code: string, message: string): void {
    this.deps.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.ScreenshotCaptureResponse,
      traceId: req.trace_id,
      inReplyTo: req.msg_id,
      payload: { snapshot_id: snapshotId, captured_at_ms: capturedAt, tab_ref: -1,
                 error: { code, message } },
    }))
  }

  private chrome() { return this.deps.chrome ?? globalThis.chrome }
}

function parseTabRef(value: unknown): TabRef | null {
  if (value === 'main' || value === 'active') return value
  if (typeof value === 'number' && Number.isInteger(value)) return value
  return null
}
```

**也要 update `mateclaw-extension/src/sw/index.ts`** 加 dispatch case:
```typescript
if (m.kind === EdgeMessageKind.ScreenshotCaptureRequest) {
  screenshotCaptureHandler.handle(m).catch(e => {
    console.error('[mateclaw][sw] ScreenshotCaptureHandler.handle threw', e)
  })
}
```

测试：`screenshot-capture-handler.test.ts` ~8 case 覆盖：
- happy path 返回 base64 + viewport
- tab_ref 无法 resolve → NO_TARGET_TAB
- captureVisibleTab 抛 → PERMISSION_DENIED
- 超过 500 KB → SCREENSHOT_TOO_LARGE
- in_reply_to + trace_id 透传
- session_id: "" P0-1 invariant 保留

## TDD 步骤

1. 写 5 个 test 文件（Java EdgeMessageTest + 2 个 TS edge-protocol test + screenshot-capture-handler.test + spec.md 不需要测试）
2. 跑全部，全 fail
3. 实现 5 处源代码
4. 全绿
5. Commit（一次 commit，跨 3 runtime + 新 SW handler + spec）

## Commit message template

```
feat(browser): extend Edge protocol to v1.2 — screenshot.capture.* (Phase 3 T3.2-protocol — Codex 20)

Adds 2 new wire kinds across all three runtimes for VisionEngine's
screenshot input channel.

  screenshot.capture.request  CP → NH → Ext  request base64 PNG of tab
  screenshot.capture.response Ext → NH → CP  base64 PNG + viewport
                                              OR { error: {code, message} }

SW handler ScreenshotCaptureHandler uses chrome.tabs.captureVisibleTab,
strips the data: prefix, enforces a 500 KB base64 cap (NM frame limit
is 1 MB; leave half for envelope overhead), surfaces typed errors:
  NO_TARGET_TAB         — tab_ref didn't resolve
  PERMISSION_DENIED     — chrome:// or other restricted page
  SCREENSHOT_TOO_LARGE  — encoded payload exceeds cap

sw/index.ts gains the bridge.onMessage ScreenshotCaptureRequest branch.
docs/specs/edge-protocol.md bumped to v1.2 with the new kinds table +
payload shapes. Forward-compat invariant preserved — v1.1 receivers
ignore the new kinds.

Tests:
  + 4 EdgeMessageTest cases (Java)
  + 4 edgeproto.test cases (NH bridge)
  + 4 edge-protocol.test cases (extension)
  + 8 screenshot-capture-handler.test cases

Phase 3 Wave 3-A1 — task T3.2-protocol.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- **不要实现 VisionEngine** —— 那是 Wave 3-A2 sub-agent H 的任务，本 task 只 ship 截图协议
- **不要碰 manifest.json** —— captureVisibleTab 不需要新 permission（覆盖在 activeTab + `<all_urls>` host_permissions 之下）
- **不要碰已有的 SnapshotRequestHandler (B11)** —— screenshot 是新的独立 handler
- **不要把 screenshot 和 a11y snapshot 合并** —— 故意分开：vision 不需要 a11y tree，a11y grounding 不需要 screenshot
