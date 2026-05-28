# Claude for Chrome —— 视觉系统调研与 MateClaw 复刻设计

> **来源**：`C:\Users\zhou_\Desktop\Claude-Chrome-应用商店\`（Anthropic 官方 Claude for Chrome 扩展 v1.0.74，2026-05 抓取）
> **目标**：复刻该扩展的 **虚拟光标 + 状态边框 + Stop 按钮 + 静态指示器 Pill + Tab Group 着色** 这五件视觉系统，对接 MateClaw 后台（聊天/编排在 MateClaw，不要扩展内对话）。

---

## 0. 一句话总览

Claude 扩展把"agent 正在控制浏览器"这件事用 **覆盖在网页之上的浮层 DOM** 完整表达出来：一个由远端驱动 `transform` 的虚假光标 + 一圈品牌色发光边框 + 一颗 Stop 按钮 + 一颗"我在这组里"的小药丸。整套系统不接管真实鼠标、不拦截输入，只是 **告诉用户"现在 AI 在做事"**。Tab Group 着色是身份标识，让一组协同工作的标签页在浏览器标签栏里被一眼认出。**这是纯前端 UI 工程；它和"agent 真的怎么操作浏览器"（CDP / debugger / playwright）是正交的两层**。

---

## 1. 整体架构（4 actor）

Claude 扩展的拆分：

```
┌─────────────────────────────────────────────────────────────────┐
│  Service Worker (background)                                    │
│  • 决定何时显示/隐藏指示器、何时移动光标、何时打 tab group      │
│  • 持有 chrome.runtime.connectNative 端口连接到桌面 Native Host │
│  • 监听 chrome.tabs.onRemoved / chrome.webNavigation 等         │
└──────────────────────────────┬──────────────────────────────────┘
                               │ chrome.tabs.sendMessage
                               │ / chrome.runtime.sendMessage
              ┌────────────────┼────────────────┐
              │                                  │
┌─────────────▼────────────┐       ┌─────────────▼─────────────┐
│ content script ①         │       │ content script ②          │
│ accessibility-tree.js    │       │ agent-visual-indicator.js │
│ run_at: document_start   │       │ run_at: document_idle     │
│ all_frames: true         │       │ all_frames: false         │
│ matches: <all_urls>      │       │ matches: <all_urls>       │
│                          │       │                           │
│ 暴露 window.__generateAcc│       │ 渲染 phantom cursor /     │
│ essibilityTree(...) 供   │       │ glow border / stop btn /  │
│ SW 通过 scripting.execute│       │ static pill；接收 SW 的   │
│ Script 调用              │       │ SHOW/HIDE/UPDATE 消息     │
└──────────────────────────┘       └───────────────────────────┘
                                                  │
                                  ┌───────────────▼───────────────┐
                                  │  Sidepanel UI (chat)          │
                                  │  本扩展独有 → MateClaw 无需   │
                                  │  保留: 对话已在后台           │
                                  └───────────────────────────────┘
```

**MateClaw 复刻时的对应关系**：

| Claude 扩展角色 | MateClaw 等价物 |
|---|---|
| Service Worker | `mateclaw-extension/src/sw/` (已在 Phase 1 plan W1) |
| accessibility-tree.js content script | `mateclaw-extension/src/content/a11y-tree.ts`（新增） |
| agent-visual-indicator.js content script | `mateclaw-extension/src/content/visual-indicator.ts`（新增） |
| Sidepanel chat UI | **不复制** —— MateClaw 用 Web Console 做对话 |
| Native host (`com.anthropic.claude_browser_extension`) | `mateclaw-browser-bridge`（已在 Phase 1 plan W1） |

---

## 2. 虚拟光标（Phantom Cursor）

> 这是用户最显眼的"AI 在动鼠标"的视觉。**它不是真的鼠标**——只是一个 `position: fixed` 的浮层 SVG，通过 CSS transform 动画移动。真正的鼠标事件由后端走 CDP / debugger 协议派发，前端光标只负责"被看见"。

### 2.1 DOM 结构

容器 `<div id="claude-phantom-cursor">` 直接挂在 `<body>` 下，CSS：

- `position: fixed; top: 0; left: 0;` —— 屏幕坐标系
- `pointer-events: none;` —— 永不拦截用户输入
- `z-index: 2147483646;` —— 最大整数减 1（最大留给 Stop 按钮）
- `transform: translate3d(x, y, 0);` —— GPU 合成
- `transition: transform 180ms cubic-bezier(0.2, 0, 0, 1);` —— 平滑插值
- `will-change: transform;` —— 提示浏览器单独图层

容器内有 **两个互相覆盖的 SVG 光标**（一个普通描边的"plain"版、一个带品牌橙色发光 drop-shadow 滤镜的"styled"版）。SVG 是 20×26 的矢量箭头形（典型操作系统鼠标指针造型）。**只显示一个**——通过设置另一个的 `display: none` 切换。

### 2.2 远端驱动协议

SW 发：

```js
chrome.tabs.sendMessage(tabId, { type: "UPDATE_PHANTOM_CURSOR", x, y })
```

content script 处理逻辑（关键点）：

1. 第一次收到时 **lazy 创建** 容器 + 两个 SVG。
2. 后续 update 只改 `style.transform = translate3d(x,y,0)`。
3. **返回 Promise**：监听 `transitionend`，转换完成后 resolve；同时挂一个 220ms 兜底 `setTimeout`（保证即使 tab 被切到后台 transitionend 不触发也不卡死）。

返回 Promise 的意义：**远端（SW / Native Host / MateClaw 后端）在 issue 真正的 click 之前能 `await` 视觉到达**。这就是"用户看到光标停稳后才点下去"的实现关键。如果不等，光标看上去会延迟落地、或在错误位置触发 click。

### 2.3 时序（典型一次 click）

```
后端决策: 在元素 [ref_42] 处点击
   │
   ▼
后端 → SW: { action: "click", x: 540, y: 320 }
   │
   ▼
SW → content: { type: "UPDATE_PHANTOM_CURSOR", x: 540, y: 320 }
   │  ← Promise pending (180ms 过渡)
   ▼
[transitionend 触发]
   │
   ▼
SW → CDP: Input.dispatchMouseEvent(type=mousePressed, x=540, y=320)
SW → CDP: Input.dispatchMouseEvent(type=mouseReleased, ...)
   │
   ▼
content script(s) 观察到真实点击事件
```

光标永远比真点击 **早 ~200ms**，给观察者足够的视觉准备时间。

### 2.4 MateClaw 实现建议

- 把整套 phantom cursor 独立成 `src/content/visual-indicator.ts` 内的一个类 `PhantomCursor`，暴露 `mount() / move(x, y): Promise<void> / unmount()`。
- 用 `crypto.randomUUID()` 给容器 id 加个命名空间前缀避免与宿主页冲突：`mateclaw-phantom-cursor-<sessionId>`。
- 主体 SVG 自己画一个，参考 Apple HIG cursor 比例：箭头长 18px、尾巴尖延伸出一截，整体 20×26。**用 MateClaw 品牌色**（例如可以用一个柔和的蓝/紫，避免和抖音/小红书的红/橙撞色）。
- transition 用 `180ms cubic-bezier(0.2, 0, 0, 1)` 就够拟人；过长拖沓、过短失真。
- Promise 兜底 timeout = 转场时长 + 40ms（即 220ms），不要更短。

---

## 3. 状态边框（Glow Border）

视觉上是 **整页内边阴影**，呼吸式脉冲，宣告"代理正在控制本页"。

### 3.1 实现

容器 `<div id="claude-agent-glow-border">` 全屏 fixed，opacity 0 → 1 渐变出现。内层 `<div id="...-inner">` 用 **三层叠加的 inset box-shadow** 营造柔和光晕：

- `inset 0 0 15px rgba(brand, 0.7)` —— 近场
- `inset 0 0 25px rgba(brand, 0.5)` —— 中场
- `inset 0 0 35px rgba(brand, 0.2)` —— 远场

动画是单独的 `@keyframes claude-pulse`：opacity 0.6 → 1.0 → 0.6，2 秒一个周期，`ease-in-out`，无限循环。

**必备的可访问性兜底**：

```css
@media (prefers-reduced-motion: reduce) {
  #mateclaw-agent-glow-border-inner { animation: none; }
}
```

呼吸效果对前庭敏感的用户会引发不适，必须给他们关掉。

### 3.2 显示/隐藏

- 显示：`opacity 0 → 1`，`transition: opacity 0.3s ease-in-out`。
- 隐藏：`opacity 1 → 0`，300ms 后 `parentNode.removeChild()`（彻底从 DOM 抹掉而不是留个 `display:none` 的僵尸节点）。
- **可视性状态保护**：移除前如果指示器又被重新激活（用户连续动作），就放弃移除。Claude 扩展用一对 `let l = false;` / `let d = false;` 标志位做"动画期间不删"的互斥，MateClaw 实现时可以更清爽用一个状态机：`HIDDEN → SHOWING → VISIBLE → HIDING → HIDDEN`。

### 3.3 MateClaw 颜色

强烈建议 **整套覆盖层用同一品牌色**（边框 + 静态 pill 边缘 + 光标 styled 版的 drop-shadow），不要每个组件各用各的色。Claude 用 `#D97757`（暖橙）。MateClaw 可以选个对比明确、且不与抖音/小红书风控色重合的色——比如蓝紫（`#6E59FF`）或青绿（`#10B981`），具体看品牌指引。

---

## 4. Stop 按钮

底部中央的胶囊按钮，圆形 stop 图标 + 文字 "Stop Claude"（MateClaw → "Stop Agent" 或具体员工名字）。

### 4.1 关键交互细节

- **入场动画**：从 `translateY(100px) opacity:0` 到 `translateY(0) opacity:1`，`0.3s cubic-bezier(0.4, 0, 0.2, 1)`。这是 Material easing 的标准曲线，给"飞入"一点点弹性。
- **hover**：`background` 从亮米白（`#FAF9F5`）切到稍深米白（`#F5F4F0`）；不改 box-shadow。
- **离开动画**：反向 translate + opacity，300ms 后再 `removeChild`。
- **box-shadow**：双层投影 `0 40px 80px + 0 4px 14px`，颜色用品牌色的 24% alpha——这是底部浮窗特有的"投影向下落"质感，**比纯灰阴影更显层级**。
- `z-index: 2147483647`（最大整数，**比 glow / cursor 都高**，保证可点）。
- `pointer-events: auto`（外层容器是 `none`，按钮本身要拨回 `auto` 才能点）。

### 4.2 行为

```js
button.addEventListener("click", async () => {
  await chrome.runtime.sendMessage({ type: "STOP_AGENT", fromTabId: "CURRENT_TAB" });
});
```

SW 接到后广播 `STOP_AGENT` 给 Sidepanel / Native Host，由后者实际中止任务。`fromTabId: "CURRENT_TAB"` 是个哨兵值，SW 用 sender.tab.id 解析具体 tab。

### 4.3 何时显示 / 何时不显示

- `SHOW_AGENT_INDICATORS` 消息携带 `isMcp` 布尔：若为 `true`，扩展用一个名为 `L`（在原源里是变量名，这里只描述语义）的"suppress stop button" 锁——**stop 按钮整轮显示中都不出现**。
- 语义：MCP 触发的动作（由 Claude 桌面应用主导）**不应该让用户从浏览器侧停掉**。停止入口在 Claude 桌面端。
- **MateClaw 复刻时的等价决策**：MateClaw 的 SOP 重放（来自 Workflow Engine）应该 **保留** stop 按钮（用户随时能停）；但如果是用户自己在 Sidepanel 触发的手动任务、且 Sidepanel 已经有原生停止按钮，那扩展里的 stop 可以隐藏。这是一个产品决策，不是技术决策。

---

## 5. 静态指示器 Pill

不是光标在动的时候用的，是 **"agent 在这组 tab 里但当前没动"** 时的提示。比如：用户开了三个 tab 在同一个 group，agent 在 tab A 上跑，用户切到 tab B，B 上不应该看到光标和发光边框，但应该让用户知道"这个组里还有 agent 在工作"。

### 5.1 DOM

底部中央的水平胶囊，包含四块：

1. 品牌 logo（约 16×16 SVG）
2. 文案 "Claude is active in this tab group"
3. 极细的竖向分隔线（0.5px 半透明）
4. 两个图标按钮：开 chat（聊天气泡图标）+ 关闭（X 图标）
5. 两个按钮各自带 tooltip（"Open chat" / "Dismiss"），hover 时 opacity 0→1

### 5.2 心跳自杀机制（关键设计）

Pill 显示后，content script 启动一个 **5 秒间隔** 的 `setInterval` 发心跳给 SW：

```js
const r = await chrome.runtime.sendMessage({ type: "STATIC_INDICATOR_HEARTBEAT" });
if (!r?.success) selfRemovePill();  // SW 不在了或不再认这个 tab 属于 group
```

意义：**如果 SW 异常退出 / 重启 / group 已被解散，pill 不会变成永久幽灵节点**。这是 MV3 SW 生命周期之下必备的健壮性设计——SW 随时会被 Chrome 杀，所以所有 SW-mediated 的浮层都必须能自我检测 SW 死亡并清理。

MateClaw 复刻时的等效检查是 `mateclaw_extension.session_alive`：心跳里带当前 SW session_id，SW 收到时若 session_id 已过期则回 `{success: false}`，pill 自杀。

### 5.3 SW 端的"主 tab vs 次 tab"判定

SW 在收到 `STATIC_INDICATOR_HEARTBEAT` 后做这样的逻辑（含义而非代码）：

1. 用 sender.tab.id 拿到当前 tab。
2. 查 `chrome.tabs.get(tabId).groupId` 得到 Chrome group id。
3. 若 group id = `TAB_GROUP_ID_NONE` → 返回 fail，pill 自杀。
4. 查 MateClaw 的 group 元数据表（参 §6）确认这个 group 是我们管理的 → 不是则 fail。
5. **逐个 ping 同组的其他 tab**（带短暂缓存 3 秒避免重复 ping），若有任一 tab 回应"我是 main"则返回 `success: true`，否则 fail。

这是 **多 tab 协同** 的核心：一个 group 里通常只有一个 main tab（有 sidepanel / 真正在跑 agent），其余是辅助 tab。Pill 只在辅助 tab 上显示，main tab 上显示的是 cursor + glow + stop。

### 5.4 Dismiss

按 X → 发 `DISMISS_STATIC_INDICATOR_FOR_GROUP`，SW 把整个 group 标记为"已被用户驳回"。该 group 内之后哪怕又有 agent 活动，pill 也不再出现（除非用户重新打开 sidepanel）。

---

## 6. Tab 分组（Tab Group）

Chrome 的 [`chrome.tabGroups` API](https://developer.chrome.com/docs/extensions/reference/api/tabGroups) 是这个系统的身份骨架。

### 6.1 概念

- 一个 **MateClaw managed group** = 一个 Chrome tab group + 我们 SW 内的元数据。
- 一组管理元数据：`{ groupId, mainTabId, secondaryTabIds[], createdAt, sessionId, displayName, color }`。
- Chrome 原生给 group 提供 `title`（字符串）和 `color`（8 个预设之一：`grey | blue | red | yellow | green | pink | purple | cyan`）。**MateClaw 应该统一用一个固定 color + 固定 title prefix** 让浏览器里能一眼认出"这是 MateClaw 的"。

### 6.2 关键 API 调用模式

调用 | 用途
---|---
`chrome.tabs.group({ tabIds: [tabId] })` | 创建一个新 group，把指定 tab 拉进去；返回 groupId
`chrome.tabGroups.update(groupId, { title, color, collapsed })` | 改名/改色/折叠
`chrome.tabs.get(tabId)` → `.groupId` | 反查 tab 属于哪个 group
`chrome.tabs.query({ groupId })` | 列出同组所有 tab
`chrome.tabGroups.get(groupId)` | 拿 group 元信息
`chrome.tabGroups.onUpdated` / `chrome.tabGroups.onRemoved` | 监听用户手动改 group

### 6.3 Claude 扩展里的"managed group manager"职责（在 `mcpPermissions` 模块里）

接口签名（基于 SW 代码反推；命名是我重命名后的）：

```ts
interface TabGroupManager {
  initialize(force?: boolean): Promise<void>;     // 从 storage 恢复元数据
  createGroup(tabId: number): Promise<GroupMeta>; // 把 tab 包成新 managed group
  findGroupByTab(tabId: number): Promise<GroupMeta | null>;
  adoptOrphanedGroup(tabId: number, chromeGroupId: number): Promise<void>;
  getMainTabId(currentTabId: number): Promise<number | null>;
  handleTabClosed(tabId: number): Promise<void>;
  clearAllGroups(): Promise<void>;
  dismissStaticIndicatorsForGroup(chromeGroupId: number): Promise<void>;
  startTabGroupChangeListener(): void;
  stopTabGroupChangeListener(): void;
}
```

几个关键设计点：

1. **`adoptOrphanedGroup`**：用户重启 Chrome 时，Chrome 会恢复 tab groups（视设置而定），但 SW 重启后内存元数据丢了。这个方法用于"看到一个 group title 是 MateClaw 前缀的 → 认领回来"。
2. **`getMainTabId`**：同组里通常只有一个 tab 是"主"（侧栏挂在它上面）。用一个布尔字段 `isMain` 维护，或者按"sidepanel 当前 attached 的 tabId"反查。
3. **`handleTabClosed`**：单 tab 关掉时降级（如果是 main，提升另一个为 main；如果是最后一个，destroy group 元数据）。
4. **`isUnmanaged`** 标志：Chrome group 存在但 MateClaw 没认领 → 用户手动建的 group 误进入了 MateClaw 的视野，要避免乱接管。

### 6.4 命名与图标

- **title**：`"MateClaw"`（或 `"MateClaw · <employee name>"` 如果有多员工并行）。统一前缀让用户能在 tab bar 里搜索 / 整理。
- **color**：选一个 Chrome 原生 8 色里 **品牌偏移最小** 的。MateClaw 如果主色是蓝紫，选 `purple`；如果绿，选 `green`。
- **collapsed**：默认 `false`，让 group 里所有 tab 都可见。让用户自己决定要不要折叠。
- **额外的 tab 图标**（asset 里的 `tabgrp-CEKmEWEf.svg` / `tabgrp_dark-uFJsQLiN.svg`）：Claude 扩展里我没找到用于 chrome `chrome_url_overrides` 或 `action.default_icon` 之外的对外使用——它们的尺寸/路径数据像是用于侧栏内自绘的"tab group 卡片图标"。MateClaw 复刻时这是 **侧栏 UI 的内部图标**，不是浏览器 chrome 强制的资源；自己设计就行。

### 6.5 深链：`https://clau.de/chrome/tab/<tabId>`

Claude 扩展拦截 `webNavigation.onBeforeNavigate`，如果用户被引导到一个 `https://clau.de/chrome/tab/123` URL，扩展会：

1. 查 `chrome.tabs.get(123)` 看 tab 还在不在。
2. 用 group manager 确认这个 tab 属于一个 managed group。
3. `chrome.windows.update(windowId, { focused: true })` + `chrome.tabs.update(tabId, { active: true })` 把那个 tab 切到前台。
4. 关掉这个引导 URL 所在的临时 tab。

MateClaw 的对应路径是 `https://mateclaw.local/jump/<tabId>` 或 `mateclaw://tab/<tabId>`，效用一致：从外部（比如桌面通知）一键跳回 agent 工作的 tab。

---

## 7. 完整消息协议清单

整理出 MateClaw 扩展需要复刻的所有消息类型（区分方向）：

### SW → content script（agent-visual-indicator）

| 类型 | 载荷 | 语义 |
|---|---|---|
| `SHOW_AGENT_INDICATORS` | `{ isMcp?: boolean }` | 显示 cursor + glow + stop。`isMcp` 抑制 stop |
| `HIDE_AGENT_INDICATORS` | `{}` | 全套淡出移除 |
| `UPDATE_PHANTOM_CURSOR` | `{ x, y }` | 移动光标，**回 Promise** 在过渡结束后 resolve |
| `HIDE_FOR_TOOL_USE` | `{}` | 临时全藏（用于截图） |
| `SHOW_AFTER_TOOL_USE` | `{}` | 恢复 hide 前的可见状态 |
| `SHOW_STATIC_INDICATOR` | `{ dismissed?: boolean }` | 显示 pill。dismissed=true 时只显示 glow 不显示 pill |
| `HIDE_STATIC_INDICATOR` | `{}` | 整个静态系统下台 |
| `HIDE_STATIC_PILL` | `{}` | 只藏 pill 但保留 glow（罕见路径） |

### content script → SW

| 类型 | 触发 |
|---|---|
| `STOP_AGENT` | 用户点 Stop 按钮 |
| `SWITCH_TO_MAIN_TAB` | 用户点 pill 上的 chat 按钮 |
| `STATIC_INDICATOR_HEARTBEAT` | 5s 周期，pill 存在时 |
| `DISMISS_STATIC_INDICATOR_FOR_GROUP` | 用户点 pill 上的 X |

### SW 内部 / 跨 tab

| 类型 | 用途 |
|---|---|
| `SECONDARY_TAB_CHECK_MAIN` | 辅助 tab 自检是否有 main alive |
| `MAIN_TAB_ACK_REQUEST` | SW 向疑似 main 的 tab 发起 ping |
| `MAIN_TAB_ACK_RESPONSE` | tab 回 ack |
| `SW_KEEPALIVE` | 通用 SW 保活 |

### SW → Native Host（MCP）

Claude 扩展在 SW 里直接 `chrome.runtime.connectNative("com.anthropic.claude_browser_extension")`，拿到一个长连 Port，收发 JSON 消息。请求/响应类型：

| 方向 | type | 用途 |
|---|---|---|
| SW → NH | `ping` | 探活 |
| NH → SW | `pong` | 响应 ping |
| NH → SW | `tool_request` | 让 SW 执行某个浏览器工具调用 |
| SW → NH | `tool_response` | 返回工具结果或错误 |
| SW → NH | `get_status` | 查询 NH 当前 MCP 连接状态 |
| NH → SW | `status_response` | 状态 |
| NH → SW | `mcp_connected` / `mcp_disconnected` | MCP 上/下线事件 |
| SW → NH | `notification`（JSON-RPC 风格） | 业务通知（MCP 转发） |

**MateClaw 等效**：MateClaw 的 [Edge Protocol v1 spec](../specs/edge-protocol.md)（Phase 1 plan W1 创建）已经覆盖了这些。`tool_request` 对应未来 Phase 2 的 `action.execute`；`STATIC_INDICATOR_HEARTBEAT` 对应已有的 `heartbeat`。

---

## 8. A11y 树（让光标知道"去哪儿"）

虽然这不是视觉系统，但 **光标的目标坐标从哪里来** 直接依赖 a11y 树。需要复刻。

### 8.1 行为概要

注入到每页（包括 iframe，`all_frames: true`，`document_start`）。挂三个 window 全局：

- `window.__claudeElementMap`：`{ "ref_N": WeakRef<Element> }` 正向映射
- `window.__claudeElementReverseMap`：`WeakMap<Element, "ref_N">` 反向映射
- `window.__claudeRefCounter`：递增整数

暴露一个函数 `window.__generateAccessibilityTree(filter, depth, maxChars, refId)`：

- 从 `document.body` 或指定 refId 元素开始 DFS
- 对每个"有意义的"元素（interactive / landmark / has-name / typed-role）输出一行：
  `<indent><role> "<name>" [ref_N] href="…" type="…"`
- 用 WeakRef 给每个元素分配稳定 `ref_N`；元素被 GC 后 ref 自然失效
- 调用结束做一次清扫，删掉所有 `WeakRef.deref() === null` 的条目
- 限制：默认 depth ≤ 15、节点数 ≤ 10000、可配总字符上限
- 安全：password / 信用卡相关 autocomplete / `<input type=hidden>` → name 显示 `[value redacted]`

### 8.2 角色推断顺序（重要）

1. 真 `role=` 属性（最权威）
2. tag 到 role 的映射表（`a→link`、`button→button`、`input[type=submit]→button` 等）
3. 否则 `generic`，被过滤掉

### 8.3 元素名称推断顺序

1. `aria-label`
2. `<input>/<textarea>` 的 `placeholder`
3. `title`
4. `<img>` 的 `alt`
5. `<label for="id">` 的文本（在元素有 `id` 时）
6. `<button>/<a>/<summary>` 的直接子文本节点拼接（不递归子元素）
7. 兜底：直接子文本节点拼接（≥3 字符才采用），最多 100 字符

### 8.4 过滤模式

`filter` 参数三档：

- `all`：所有元素（含 `aria-hidden`、不可见）
- `interactive`：只输出可点击/可输入元素
- 默认（未指定）：在视窗内 + 可见 + 非 `aria-hidden`

### 8.5 MateClaw 实现要点

- 函数名换成 `window.__mateclawA11yTree(...)`，map 名加 `__mateclaw_` 前缀避免与其它扩展冲突。
- WeakRef + WeakMap 这对原语保留——非常对（不要换成普通 Map，会内存泄漏）。
- 输出格式可以略改成 JSONL 而不是缩进文本（更利于 LLM token / 也更利于本地缓存差分），但牺牲一点可读性。Claude 用纯文本是个有意的"低 token 选择"，MateClaw 复刻时建议保留纯文本格式：

  ```
    button "关注" [ref_42] type="submit"
    link "查看详情" [ref_43] href="/post/123"
      img "封面" [ref_44]
  ```

- **加一个 `bbox` 字段**：MateClaw 的三引擎 grounding 模型（参 architecture 讨论）希望每个 ref 同时附带 `getBoundingClientRect()` 的 `{x, y, width, height}`，这样 SW 不需要二次 query。可以放在 ref 后面：
  ```
    button "关注" [ref_42 @540,320,80,32]
  ```

- **保留"刷新时所有旧 ref 自动失效"语义**：当 `chrome.webNavigation.onCommitted` 触发时，content script 重置三个全局变量。

---

## 9. 内容脚本注入策略

```json
"content_scripts": [
  { "js": ["assets/a11y-tree.js"],          "matches": ["<all_urls>"], "run_at": "document_start", "all_frames": true  },
  { "js": ["assets/visual-indicator.js"],   "matches": ["<all_urls>"], "run_at": "document_idle",  "all_frames": false }
]
```

**为什么 a11y 在 document_start + all_frames，indicator 在 document_idle + 仅顶 frame**：

- a11y 要 **抢先于宿主页脚本** 挂全局函数，否则 SW 想立刻读 a11y 会失败（脚本还没注入）。`document_start` + `all_frames` 保证每个 frame 内都有 `__claudeElementMap` 可用。
- indicator 是纯视觉浮层，**等 DOM/CSS 安定后再插**。只顶 frame 因为 iframe 内不需要也不应该插覆盖层（会被 sandbox 限制视口，光标也不该跨 frame 跑）。

### 9.1 MateClaw 复刻时的两个细节

1. **CSP 友好的注入**：MateClaw extension 的 `content_security_policy.extension_pages` 已包含 `script-src 'self'`。content script 本身受宿主页 CSP 影响。所有 inline 样式（cursor / glow / pill 的 cssText）走 `element.style.cssText = "..."` 是 CSP 安全的（不是 `<style>` 标签注入）。`@keyframes claude-pulse` 才需要 `<style>` 标签——必须放在扩展 origin 的 stylesheet 里、由 content script `document.head.appendChild` 注入，宿主页 CSP 通常允许（来自 extension 的 isolated origin）。
2. **关闭页时清理**：所有指示器 content script 都 `window.addEventListener("beforeunload", cleanup)`，防止页面卸载时 audio context / timer / DOM 节点泄漏。

---

## 10. 一个不太显眼但关键的优化：AudioContext 保活

Claude 扩展在显示 indicator 时创建一个 `AudioContext`，挂一个 0-gain 的 `ConstantSource` 并 start，目的是 **保住 audio context active 状态**——用户后续如果点击页面，可以无延迟播放音效（成功/失败提示）。Chrome 的 audio context 默认要求 "用户手势激活"，**预先保活的 trick 让首次音效可以立刻响**。

MateClaw 如果将来要做音效提示（"任务完成"、"等待审批"），照搬这个 trick。如果暂时不做音效，可以省略——但保留这个**位置和接口**便于以后加。

---

## 11. MateClaw 复刻清单（落地建议）

按优先级排：

### 阶段 A：核心三件（最早就要）

1. **PhantomCursor**（§2）—— Phase 2 任务，CDP 接管之后立刻能用。光标 + 平滑移动 + Promise 回 resolve。
2. **GlowBorder**（§3）—— 同期，纯 CSS 几乎零成本。
3. **StopButton**（§4）—— 同期，配合 MateClaw approval flow 做"用户随时拦截"。

### 阶段 B：分组与持久身份

4. **TabGroupManager**（§6）—— 用户可能在浏览器里同时跑 3 个 MateClaw 数字员工。每个员工的 tabs 独立分组、独立着色、独立 main-tab。
5. **StaticIndicatorPill + Heartbeat**（§5）—— 5 秒心跳自杀机制必须实装，否则 SW 死后会留下幽灵 UI。

### 阶段 C：辅助

6. **HIDE_FOR_TOOL_USE / SHOW_AFTER_TOOL_USE**（§ 协议清单）—— Phase 2 截图工具上线时同步加。
7. **A11y Tree 扩展**（§8）—— 已在 Phase 2 的 Three-Engine Orchestrator 计划里。在原 Claude 设计基础上 **加 bbox**。
8. **AudioContext 保活**（§10）—— 可选，留接口。

### 阶段 D：体验完善

9. **prefers-reduced-motion 兜底**（§3.1） —— 必做的可访问性。
10. **`mateclaw://tab/<id>` deep link**（§6.5）—— 桌面通知点击直跳浏览器对应 tab。

---

## 12. 几个 MateClaw 应该故意 **不复刻** 的地方

1. **Sidepanel 内置对话** —— 用户明确说对话留在 MateClaw 后台 / Web Console。Sidepanel 只做"任务面板 + 实时步骤 + 控制按钮"。
2. **`com.anthropic.claude_browser_extension` 双 native host 探测** —— Claude 探两个 host 名是因为 desktop 应用和 Claude Code 是两套独立程序。MateClaw 只有一个 `com.mateclaw.browser_bridge`。
3. **`clau.de/chrome/permissions` 这类 URL hook** —— 那是 Anthropic 自己的网站做引导用的。MateClaw 用 Web Console 内的弹窗做权限引导即可，不需要拦截 webNavigation。
4. **样式色板与具体 SVG 路径** —— 直接复用就是 IP 滥用；MateClaw 应该用自己的品牌色 + 自己设计的光标矢量（参 §2.4）。
5. **`chrome.notifications` 关于"扫码任务"和"周期任务"的全套基础设施** —— 这是 Claude desktop 应用的具体功能，与浏览器视觉系统无关。

---

## 13. 风险与陷阱

| 风险 | 来源 | 缓解 |
|---|---|---|
| 宿主页拦截了我们的 `chrome.runtime.sendMessage` 响应 | 部分网站会 monkey-patch `window.postMessage` —— content script API 不受影响但要避免使用 `window.postMessage` 与扩展通讯 | 全部走 `chrome.runtime.sendMessage` + `chrome.tabs.sendMessage`，不用 window 桥接 |
| z-index 大战：宿主页有元素用了 `2147483647` | 极少数广告/弹窗会用 max int | cursor 用 `2147483646`，stop 用 `2147483647`，并接受偶尔被覆盖 |
| SW 被杀，pill 不消失 | MV3 5 分钟空闲回收 | §5.2 心跳自杀；周期可调到 3s 但流量增加 |
| Chrome 重启后 tab group 被恢复，但 SW 内存丢了 | Chrome 默认行为 | `adoptOrphanedGroup`：title 前缀匹配认领 |
| 光标在 `<iframe>` 里飞 | iframe 自己有 DOM | 顶 frame 唯一注入（`all_frames: false`），光标永远走视口坐标 |
| 用户禁用动画 | OS 设置 / 浏览器设置 | `prefers-reduced-motion: reduce` 必须支持 |
| 用户的页面用了 Shadow DOM | querySelector 穿不透 | a11y 树脚本要递归进 `element.shadowRoot`（Claude 当前实现是否覆盖未验证，MateClaw 必须支持） |

---

## 14. 一句话收束

**Claude for Chrome 的视觉系统 = 一个虚假光标（CSS transform 平滑移动）+ 一圈呼吸边框（@keyframes pulse）+ 一颗停止按钮（slide-up pill）+ 一颗静态徽章（5s 心跳自杀）+ 一套 Chrome 原生 tab group 着色身份**。所有这些都不依赖 CDP / debugger / playwright；它们和 agent 的真实操作是 **并行的视觉层**，由 SW 通过 chrome.runtime/tabs.sendMessage 远程调度。MateClaw 复刻时直接抄这套架构骨架，配上自己的品牌色和命名空间前缀，外加给 a11y 节点加 bbox 这一个增强即可。
