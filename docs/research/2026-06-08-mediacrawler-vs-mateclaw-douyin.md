# MediaCrawler 与 MateClaw 抖音获客实现对比调研

> 日期：2026-06-08  
> 调研对象：`D:\ai_work\MediaCrawler`、当前 MateClaw 抖音获客 / Browser Agent 实现  
> 目标：梳理 MediaCrawler 的实现方式，判断哪些设计能参考到 MateClaw 的抖音获客与后续多平台采集能力里。

---

## 0. 一句话结论

MediaCrawler 最值得参考的不是页面点击技巧，而是 **“浏览器负责登录态和平台环境，采集层按 cursor / hasMore 做结构化分页”** 的工程模式。

MateClaw 当前实现已经具备更强的产品化底座：Run Kernel、StepLedger、事件流、持久化、Tool Guard、浏览器扩展区域抽取和私信草稿能力都更完整。但评论采集主要依赖浏览器页面的 `extract_region + scroll_region`，容易受虚拟列表、滚动焦点、DOM class 变化和页面布局漂移影响。

因此推荐方向是：**保留 MateClaw 当前 Run/Workflow/Browser Edge 架构，先把现有 DOM/region 评论采集分页化，再在明确授权和低频边界下评估页面上下文 fetch**。后端重点负责状态机、事件、checkpoint、幂等持久化和停止原因，而不是建设独立高频爬虫能力。

---

## 1. MediaCrawler 的实现形态

MediaCrawler 是 Python/Playwright 的多平台自媒体采集框架，支持小红书、抖音、快手、B 站、微博、贴吧、知乎等平台。

核心目录：

| 路径 | 作用 |
|---|---|
| `main.py` | CLI 入口，`CrawlerFactory` 按平台创建 crawler |
| `base/base_crawler.py` | `AbstractCrawler`、`AbstractLogin`、`AbstractStore`、`AbstractApiClient` |
| `media_platform/<platform>/core.py` | 平台主流程：登录、搜索、详情、评论、创作者 |
| `media_platform/<platform>/client.py` | 平台 HTTP/API client |
| `media_platform/<platform>/login.py` | 二维码、手机号、Cookie 登录 |
| `tools/cdp_browser.py` | CDP 连接真实 Chrome/Edge |
| `proxy/` | 代理池、代理过期刷新、代理 provider |
| `store/` | CSV、JSON、JSONL、DB、MongoDB、Excel 等存储实现 |
| `api/services/crawler_manager.py` | WebUI 子进程启动和日志推送 |

关键模式：

1. **工厂 + 平台适配**
   `CrawlerFactory` 根据 `--platform` 创建对应 crawler；每个平台都按 `core/client/login/store` 分层。

2. **浏览器拿登录态，HTTP client 分页拉数据**
   抖音 crawler 启动浏览器后，创建 `DouYinClient`，从浏览器上下文提取 Cookie、UA、localStorage，再由 client 直接请求搜索、详情、评论等接口。

3. **评论分页采集**
   抖音的 `get_aweme_all_comments` 通过 cursor/has_more 循环拉评论，每页回调 store 保存；这比页面滚动采集更稳定，也更容易做 checkpoint。

4. **CDP 复用真实浏览器**
   `CDPBrowserManager` 支持连接用户已有浏览器，复用真实 Cookie、扩展和浏览器设置，降低登录与环境差异。

5. **显式配置**
   平台、登录方式、采集类型、评论开关、二级评论、最大数量、并发、睡眠间隔、代理、保存格式都显式参数化。

6. **多存储后端**
   同一份平台数据可写 JSONL、Excel、SQLite/MySQL/Postgres/MongoDB 等；DB 路径用 upsert 思路避免重复。

---

## 2. MateClaw 当前实现形态

当前 MateClaw 抖音获客已经不是简单工具调用，而是产品化运行链路。

核心模块：

| 路径 | 作用 |
|---|---|
| `mateclaw-server/src/main/java/vip/mate/tool/builtin/DouyinLeadAcquisitionTool.java` | Agent 可调用的专用工具入口 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/DouyinLeadAcquisitionExecutor.java` | 抖音获客固定链路执行器 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/browser/DouyinBrowserAdapter.java` | 浏览器适配器接口 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/browser/ExtensionDouyinBrowserAdapter.java` | 基于扩展 Browser Edge 的抖音平台动作实现 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/collect/DouyinCommentCollector.java` | 从观察树 / extract_region 结果中解析评论 |
| `mateclaw-extension/src/sw/action/handlers/detect_region.ts` | 扩展侧检测 `douyin.comments` 区域 |
| `mateclaw-extension/src/sw/action/handlers/extract_region.ts` | 扩展侧抽取评论、搜索结果、DM 输入区 |
| `mateclaw-extension/src/sw/action/handlers/scroll_region.ts` | 扩展侧评论区区域滚动 |
| `mateclaw-extension/src/sw/action/handlers/type_dm_draft.ts` | 扩展侧私信草稿输入 |
| `mateclaw-server/src/main/resources/skills/douyin-lead-acquisition/` | 抖音获客 Skill skeleton、workflow、schema、adapter 配置 |
| `mateclaw-server/src/main/resources/db/migration/*/V132__agent_os_run_kernel.sql` | Run/Step/Event/Lead 表 |
| `mateclaw-server/src/main/resources/db/migration/*/V133__retire_lead_browser_harness_tool.sql` | 评论唯一键，支持重放幂等 |

当前执行链路：

```text
douyin_lead_acquisition_run
  -> create mate_agent_run + mate_lead_task
  -> open_douyin_search
  -> apply_sort
  -> open_first_video
  -> open_comments
  -> detect_comment_region
  -> collect_all_comments
  -> save mate_lead_comment
  -> match_comment_text
  -> open matched author profile
  -> follow
  -> open DM
  -> type DM draft
  -> save profile / engagement
  -> finish run
```

当前优势：

- 有 Run Kernel、StepLedger、RunEvent，运行过程可观测。
- 有 `mate_lead_task/comment/profile/engagement`，结果能沉淀成产品数据。
- 有评论唯一键，重放不会重复插入。
- 有 Tool Guard 和外联审批基础。
- 扩展侧有区域检测、区域滚动、结构化抽取、私信草稿输入等平台动作原语。
- 对采集完整度有显式报告：`complete`、`declaredCommentCount`、`commentsCollected`、`stopReason`、`collectionCoverage`。

当前短板：

- 评论采集主要依赖页面可见窗口和 DOM/AX 树，虚拟列表会让“已采集数量”和真实总量之间存在不确定性。
- 滚动采集容易受焦点、页面切视频、评论区丢失、布局漂移影响。
- `videoLimit` 在 skill/workflow 层已经有表达，但 Java 执行器当前实际偏单视频固定链路。
- `ExtensionDouyinBrowserAdapter` 文件过大，平台搜索、评论采集、作者打开、私信互动混在一起。
- 还没有类似 MediaCrawler 的平台 HTTP/API client 层。

---

## 3. 最值得参考的设计

### 3.1 浏览器登录态 + 结构化分页评论采集

这是最高优先级。

MediaCrawler 的抖音实现是：浏览器打开抖音并确认登录态，然后 client 带页面环境按 cursor 拉评论。MateClaw 可以采用同样的 **分页状态思想**，但不要直接照搬代码，也不要导出登录凭据、生成/复用/破解签名，或读取超出当前页面会话必要范围的敏感上下文。平台要求签名、验证码或安全验证时，应停止并上报，而不是继续规避。

```text
Browser Edge 确认页面 / 登录态
  -> DouyinStructuredCommentCollector 按页采集评论
  -> P0 使用现有 extract_region + scroll_region 形成 DOM 页
  -> P2 可选使用受控 in-page fetch 读取当前可见公开视频评论页
  -> 每页保存 comment page event + mate_lead_comment
  -> 出现登录失效 / 验证 / 限流 / 保护信号时停止
```

推荐新增能力：

- `DouyinStructuredCommentCollector`
- `DouyinCommentPage`
- `DouyinDomCommentPageCollector`
- P2 可选 `browser.fetch_in_page` 或 domain-specific `douyin.fetch_comments`
- `CommentCollectionResult.metadata.pages`
- `RunEvent`: `lead.comments.page_collected`

好处：

- 评论分页可恢复。
- 采集数量、cursor/窗口指纹、hasMore/stopReason、失败码更可靠。
- 页面虚拟列表仍可作为 P0 数据来源，但不再是黑盒滚动。
- 后续能自然支持多视频、二级评论、采集暂停/续跑。

### 3.2 平台 client 与浏览器动作解耦

MediaCrawler 的 `core/client/login/store` 分层适合参考到 MateClaw。

MateClaw 可拆成：

| 模块 | 责任 |
|---|---|
| `DouyinSearchAdapter` | 搜索、排序、打开视频 |
| `DouyinCommentCollector` | 评论采集协调，选择 DOM 页 / 后续 in-page fetch 路径 |
| `DouyinStructuredCommentCollector` | 分页状态机、checkpoint、停止原因 |
| `DouyinDomExtractor` | DOM/AX/region 抽取兜底 |
| `DouyinEngagementAdapter` | 打开作者、关注、私信草稿 |
| `DouyinRunOrchestrator` | 状态机、checkpoint、幂等 |

这样 `ExtensionDouyinBrowserAdapter` 不再继续膨胀。

### 3.3 采集配置显式化

MediaCrawler 的配置很完整，MateClaw 可以把这些配置纳入 skill schema 和 Java input：

| 配置 | 建议 |
|---|---|
| `videoLimit` | 接入真实视频循环 |
| `maxCommentsPerVideo` | 防止无界采集 |
| `includeReplies` | 控制二级评论 |
| `crawlIntervalMs` | 控制节流 |
| `maxConcurrency` | 初期保持 1，后续多视频可谨慎开放 |
| `collectionMode` | P0 默认 `dom_region`; P2 才允许管理员开启 `auto` / `in_page_fetch` |
| `sendMode` | `draft_only` / `send_after_approval` |

### 3.4 每页事件与 checkpoint

MediaCrawler 的 callback 保存思路可以产品化成 MateClaw 的 RunEvent/Step checkpoint。

建议事件：

```text
lead.video.opened
lead.comments.page_collected
lead.comments.collected
lead.comment.matched
lead.profile.opened
lead.engagement.completed
```

`lead.comments.page_collected` 建议包含：

```json
{
  "videoKey": "...",
  "page": 3,
  "cursor": "...",
  "hasMore": true,
  "commentsInPage": 20,
  "seenNewCount": 18,
  "insertedCount": 18,
  "source": "dom_region",
  "failureCode": null
}
```

### 3.5 结果导出可参考，但不必照搬

MediaCrawler 支持 JSONL/Excel/DB/MongoDB。MateClaw 已经有 `mate_lead_*` 表，更适合产品内使用。

可补：

- 从 `mate_lead_comment` 导出 JSONL/CSV/Excel。
- 为线索任务加 “导出报告” 动作。
- 将导出挂到现有 generated file / send_file 流程。

---

## 4. 不建议照搬的部分

### 4.1 不直接复制 MediaCrawler 代码

MediaCrawler 许可证是 `NON-COMMERCIAL LEARNING LICENSE 1.1`，代码和 README 都明确限制学习/研究用途、禁止商业用途和大规模爬取。

MateClaw 可以参考架构模式，但不应直接复制其实现，尤其是签名、反检测、平台接口细节代码。后续实现应独立重写，并保留合规约束。

### 4.2 不采用 WebUI 子进程模型

MediaCrawler WebUI 的 `CrawlerManager` 本质是拼命令、启动子进程、读 stdout、推日志。

MateClaw 已有：

- `mate_agent_run`
- `mate_agent_step`
- `mate_agent_event`
- `mate_lead_task`
- `mate_lead_comment`
- `mate_lead_profile`
- `mate_lead_engagement`

所以不需要回退到子进程模型。应继续走 Run Kernel。

### 4.3 不让 Agent 任意执行页面 JS

MediaCrawler 有浏览器上下文执行 JS 或签名的思路，但 MateClaw 必须走受控工具：

- domain-specific fetch/action
- 明确参数 schema
- Tool Guard
- 审批
- 限流
- 事件审计

不要暴露通用 `evaluate` 给 Agent 做平台采集。

---

## 5. 建议落地路线

### P0：把现有 DOM 评论采集分页化

目标：不改整条获客链路，不引入平台 API，只把 `collectAllComments` 从一次性滚动采集改为有页事件、checkpoint、停止原因的结构化采集。

建议：

1. 在打开评论区并确认视频后，提取 `videoKey/awemeId`。
2. 新增 `DouyinStructuredCommentCollector` 和 DOM page collector。
3. 每次 `extract_region` + `scroll_region` 形成一页，逐页保存 `mate_lead_comment`。
4. 发布 `lead.comments.page_collected` 和 checkpoint 事件。
5. `CommentCollectionResult.metadata` 标注 `source=dom_region`、`pagesFetched`、`lastCursor`、`stopReason`。

验收：

- timeline 能看到每页事件和最终汇总事件。
- `maxCommentsPerVideo`、`maxPagesPerVideo`、`crawlIntervalMs` 生效。
- 遇到登录失效、验证码/验证、限流、疑似保护时立即停止并上报。
- 重跑或跨页重复不会重复写入同一评论。

### P0.5：受控 in-page fetch 预研

目标：只做接口边界和 feature flag 设计，不默认启用。

要求：

- 需要管理员开启、Tool Guard/审批策略和用户明确授权。
- 只读取当前登录用户可正常浏览的公开评论。
- 不导出登录凭据，不生成/复用/破解签名。
- 出现验证、限流、保护信号时停止，不 fallback 到 DOM 继续绕过。

### P1：接入真实多视频循环

目标：让 `videoLimit` 从 schema 走到执行器。

建议：

```text
for videoIndex in 0..videoLimit:
  videoIterator.open(videoIndex)
  collect comments
  save video summary
  match comments
  engage matched authors
```

每个视频单独记录：

- `videoKey`
- `openedUrl`
- `declaredCommentCount`
- `collectedCommentCount`
- `stopReason`
- `matchedCount`
- `engagementCount`

注意: 当前适配器已在视频页时可能复用当前视频, 所以 P1 不能只循环调用现有 `openVideo(videoIndex)`。需要先实现候选视频迭代器: 保存/恢复排序后的搜索候选列表, 打开指定 index, 校验 `videoKey/url/title` 与上一视频不同, 无更多候选时给出明确 stopReason。

### P2：二级评论与限流

参考 MediaCrawler 的 `ENABLE_GET_SUB_COMMENTS`、`CRAWLER_MAX_COMMENTS_COUNT_SINGLENOTES`、`CRAWLER_MAX_SLEEP_SEC`。

MateClaw 建议：

- `includeReplies`
- `maxRepliesPerComment`
- `maxCommentsPerVideo`
- `crawlIntervalMs`
- 每 workspace / 每账号限流
- 失败退避

### P3：拆分抖音浏览器适配器

目标：避免 `ExtensionDouyinBrowserAdapter` 继续膨胀。

拆分顺序：

1. `DouyinCommentApiCollector`
2. `DouyinCommentDomCollector`
3. `DouyinSearchNavigator`
4. `DouyinEngagementExecutor`
5. `DouyinLeadWorkflowExecutor`

### P4：导出和看板

基于当前 `mate_lead_*` 表：

- 任务级导出：CSV/JSONL/Excel。
- 看板聚合：视频数、评论数、匹配数、关注数、私信草稿数、失败原因。
- 运行详情：按 step/event 展示采集过程。

---

## 6. MateClaw 与 MediaCrawler 能力对照

| 能力 | MediaCrawler | MateClaw 当前 | 建议 |
|---|---|---|---|
| 登录态 | Playwright/CDP 保存 Cookie | 用户真实浏览器扩展连接 | 保留 MateClaw，补 API 环境提取 |
| 评论采集 | API client 分页 | DOM region + 滚动窗口 | P0 先做 DOM 结构化分页; P2 再评估受控 in-page fetch |
| 运行持久化 | 文件/DB 输出 | Run/Step/Event/Lead 表 | 保留 MateClaw |
| 可观测 | stdout/WebUI 日志 | RunEvent + StepLedger | 保留 MateClaw，补 page-level event |
| 多平台结构 | `media_platform/<platform>` | 抖音先行，Skill skeleton | 参考其 platform adapter 分层 |
| 代理 | 代理池和过期刷新 | 当前获客未显式建模 | 不设计代理池或轮换代理规避限制; 如需企业网络出口/profile 管理, 仅用于合规审计和账号隔离 |
| 导出 | JSONL/Excel/DB/Mongo | DB 查询接口为主 | 补导出能力 |
| 安全审批 | 基本免责声明 | Tool Guard/Approval | 保留 MateClaw，不照搬 |

---

## 7. 最终建议

短期不要推翻当前抖音获客实现。当前 MateClaw 的产品化底座已经比 MediaCrawler 更适合做 Agent OS：它有运行记录、步骤状态、事件流、持久化、审批和浏览器动作协议。

真正应该借鉴 MediaCrawler 的地方，是把“采集”从纯浏览器动作里抽出来，变成结构化分页能力：

```text
Browser Agent 用来：
  - 打开真实页面
  - 确认登录态
  - 处理搜索/排序/打开作者/私信草稿等必须交互的动作

Structured Comment Collector 用来：
  - DOM 窗口分页化
  - 评论页级事件
  - 二级评论页级扩展
  - 采集 checkpoint
  - 采集限流和失败分类
```

这条路线能同时保留 MateClaw 的用户可见浏览器控制优势，又拿到 MediaCrawler 的稳定、可分页、可恢复、可审计采集优势。
