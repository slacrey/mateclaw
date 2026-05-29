# Codex Task 19 — Phase 3 T3.A：iframe-internal grounding

> 你（Codex）要打开 iframe 内元素的 grounding 路径 —— 这是 Phase 2 P1-5 显式 deferred 的项。三处协调改动：
> 1. **`manifest.json`** A11y content script 从 `all_frames: false` 翻到 `all_frames: true`
> 2. **`a11y-tree.ts`** 在子 frame 里执行时，累积 `window.frameElement.getBoundingClientRect()` 链，把 bbox 平移到 page-absolute 坐标；每行加 `frame_id` 标签
> 3. **`SnapshotRequestHandler`** 支持 `frameIds: [frameId]` 参数，让 CP 能精确取某个 frame 的 a11y tree

## 项目背景

阅读这些文件先理清上下文：
- `mateclaw-extension/manifest.json` + `mateclaw-extension/public/manifest.json`（注意双份要同步！Phase 2 Wave 1 Sub-agent B 教训）
- `mateclaw-extension/src/content/a11y-tree.ts` —— Wave 1 C1 交付的内容脚本，导出 `window.__mateclaw_a11y_tree(filter, depth, maxChars, refId)`
- `mateclaw-extension/src/sw/snapshot-request-handler.ts` —— Wave 3 B11 交付，SW 通过 `chrome.scripting.executeScript` 调 a11y-tree

## P1-5 不变量（Phase 3 Wave A 加进 audit script）

测试要求：一个按钮在 iframe（page 上的偏移 200,300）内、本地坐标 (10, 10) 时，emit 的 a11y 行必须报 bbox `@{210,310 wxh}`（**绝对** page 坐标，不是 frame-local）。

## 你要交付的 3 类改动

### 1. `mateclaw-extension/manifest.json` + `mateclaw-extension/public/manifest.json`（双份同步）

把 a11y-tree 这条 content_scripts entry 的 `all_frames` 从 `false` 翻到 `true`：

```json
{
  "matches": ["<all_urls>"],
  "js": ["content/a11y-tree.js"],
  "all_frames": true,                  // 改 ← 这里
  "run_at": "document_idle"
}
```

`visual-indicator.js` 那条**保持 `all_frames: false`**（视觉指示器只在 top frame 显示）。

### 2. `mateclaw-extension/src/content/a11y-tree.ts`

修改 `window.__mateclaw_a11y_tree(...)` 的实现，让它在子 frame 里执行时：

1. 计算 frame chain offset：
   ```typescript
   function getFrameOffsetToPage(): { x: number; y: number } {
     let x = 0, y = 0
     let win: Window | null = window
     while (win && win !== win.top) {
       // window.frameElement is the <iframe> in the parent doc; getBoundingClientRect
       // gives its position relative to parent viewport. Accumulate up.
       const frameEl = win.frameElement as HTMLIFrameElement | null
       if (!frameEl) break
       const rect = frameEl.getBoundingClientRect()
       x += rect.left
       y += rect.top
       win = win.parent
     }
     return { x, y }
   }
   ```
2. Walk the DOM as before; every line's bbox `@{x,y wxh}` 加上 `getFrameOffsetToPage()` 的偏移
3. 每行 prefix 加 `frame_id` 标签（Phase 3 只标记 top frame=0, 子 frame=非零；具体 id 从 SW 注入或用 `chrome.runtime.id + Math.random()` fallback）。Wire format extension:
   ```
   Button[ref=ref_1, frame=0]: Submit @{100,200 80x32}
   Button[ref=ref_2, frame=1]: Login @{210,310 60x24}
   ```
4. `PageSnapshot.LINE_PATTERN` 在 Java 侧（`mateclaw-server/.../domain/PageSnapshot.java`）的正则**也要更新**接受新的 `, frame=N` 段。**所有现有 24 个 DomainTypesTest 不能 regression**。

### 3. `mateclaw-extension/src/sw/snapshot-request-handler.ts`

`a11y.snapshot.request` payload 加 `frame_id?: number` 字段；当存在时，`chrome.scripting.executeScript` 的 `target` 用 `{tabId, frameIds: [frameId]}`；不存在时维持现有 `{tabId, allFrames: false}` 默认（top frame only）。

## TDD 步骤

1. 新增 `a11y-tree.test.ts` 一个 case 覆盖 frame-offset bbox 平移（用 happy-dom 模拟嵌套 window 不太行；考虑写一个直接 mock `window.frameElement.getBoundingClientRect` 的轻量 case，或用 puppeteer 测试代替——后者太重，**优先轻量 mock**）
2. 新增 `PageSnapshotTest.java`（`mateclaw-server/.../domain/`）case 覆盖新的 `frame=N` 解析
3. 新增 `SnapshotRequestHandlerTest.java` case 覆盖带 `frame_id` 的请求路由
4. 跑 `pnpm test --run` + `mvn test -Dtest='vip.mate.browser.**'` 确认无回归

## Commit message template

```
feat(extension+server): iframe-internal element grounding (Phase 3 T3.A — Codex 19)

Closes the Phase 2 P1-5 deferral. Three coordinated changes:

1. manifest.json + public/manifest.json — a11y-tree content script flips
   to all_frames: true so it runs inside every same-origin iframe. The
   visual-indicator script stays all_frames: false (overlays only top).

2. a11y-tree.ts — when executing in a child frame, accumulate the
   window.frameElement.getBoundingClientRect() chain up the parent chain
   and translate every emitted bbox to page-absolute coordinates. Each
   line now also carries a frame=N tag in the wire format:
     Button[ref=ref_1, frame=0]: Submit @{100,200 80x32}
     Button[ref=ref_2, frame=1]: Login @{210,310 60x24}

3. SnapshotRequestHandler — payload optional frame_id field; when set,
   the chrome.scripting.executeScript target becomes {tabId, frameIds:
   [frameId]} instead of allFrames:false. Default behaviour (top frame
   only) preserved when frame_id absent.

4. PageSnapshot.LINE_PATTERN (Java) updated to accept the new , frame=N
   segment. All 24 DomainTypesTest cases plus B11 snapshot tests stay
   green; the new "bbox-translation" P1-5 test (button at local (10,10)
   inside iframe at (200,300) reports @{210,310 ...}) passes.

N+3 vitest cases between a11y-tree.test, PageSnapshotTest, and
SnapshotRequestHandlerTest.

Phase 3 Wave 3-A1 — task T3.A.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要碰 `content/visual-indicator.js` 的 `all_frames` 设置 —— 它**必须保持 false**（视觉只在 top frame）
- 不要碰 `mateclaw-server/.../domain/` 的其他 record（BBox / Viewport / ...）—— Wave 4-0 已固定
- 不要碰 Wave 3 已交付的 B11 `SnapshotRequestHandler` 现有签名 —— 只**扩展** payload 字段
- 不要把 frame_id 默认 0 写死成"top frame"的语义；空 / 缺失才是 top frame；`frame_id: 0` 应该是显式的 top frame 请求
