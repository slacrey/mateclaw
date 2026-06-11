# MateClaw 抖音评论「结构化分页采集」方案

> 日期: 2026-06-08  
> 基线: 当前 MateClaw 抖音获客实现 + `D:\ai_work\MediaCrawler` 调研  
> 关联调研: `docs/research/2026-06-08-mediacrawler-vs-mateclaw-douyin.md`

---

## 0. 一句话结论

MateClaw 要借 MediaCrawler 的是 **评论采集的结构化分页工程思想**: 每一页都有输入 cursor / 输出 cursor / hasMore / 新增评论数 / 停止原因 / checkpoint / 事件, 任务可以观测、恢复、去重、审计。

MateClaw **不应该借** 的是规避平台风控的手段: 不复制签名算法, 不自动破解验证码/滑块, 不用代理池规避限制, 不把高频抓取作为目标。遇到登录失效、验证页、限流、疑似保护时, 应该把它记录成明确的 `stopReason`, 暂停或失败, 交给用户处理。

---

## 1. 背景与当前差距

### 1.1 MediaCrawler 的可参考点

MediaCrawler 抖音评论采集的核心不是页面滚动, 而是:

```text
浏览器建立登录态 / 环境
  -> client 读取 cookie / UA / localStorage 等上下文
  -> 按 aweme_id + cursor + count 分页请求评论
  -> 每页回调 store 保存
  -> has_more=false 或达到上限后停止
```

值得参考的工程点:

- 采集以「页」为最小可靠单元, 而不是以一次大循环为单元。
- cursor / hasMore / count / pageIndex 是显式状态。
- 每页保存后再推进 cursor, 支持断点续跑。
- 评论主键稳定, 数据写入可以 upsert / 去重。
- 主评论和二级评论可以用同一套分页抽象表达。

不能照搬的点:

- MediaCrawler 的许可是非商业学习许可, 不适合复制代码。
- 不引入自动解滑块、代理池、签名规避或反检测逻辑。
- 不把平台接口细节硬编码成 MateClaw 的核心能力; 后续如做 in-page fetch, 也应当是浏览器页面上下文内、低频、有边界的采集能力。

### 1.2 MateClaw 当前实现

当前链路位于:

| 文件 | 现状 |
|---|---|
| `mateclaw-server/src/main/java/vip/mate/tool/builtin/DouyinLeadAcquisitionTool.java` | Agent 工具入口, 同步触发抖音获客任务 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/DouyinLeadAcquisitionExecutor.java` | 固定链路: 搜索 -> 排序 -> 开第一个视频 -> 打开评论 -> 检测评论区 -> 采集全部评论 -> 匹配 -> 关注/私信草稿 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/browser/ExtensionDouyinBrowserAdapter.java` | 基于 Extension Browser Edge 的搜索、评论区、滚动、作者主页、私信动作 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/collect/DouyinCommentCollector.java` | 从 observation / extracted region 中解析评论 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/store/LeadPersistenceService.java` | 保存 task / comment / profile / engagement |
| `mateclaw-extension/src/sw/action/handlers/detect_region.ts` | 扩展侧检测 `douyin.comments` 区域 |
| `mateclaw-extension/src/sw/action/handlers/extract_region.ts` | 扩展侧抽取评论区域结构 |
| `mateclaw-extension/src/sw/action/handlers/scroll_region.ts` | 扩展侧区域滚动 |

已有基础很好:

- `mate_agent_run` / `mate_agent_step` 已有 `checkpoint_ref`。
- `mate_agent_event` 可直接承载分页事件。
- `mate_lead_comment` 已有 V133 唯一键 `(task_id, comment_key)`, 适合做幂等写入。
- `CommentCollectionResult` 已有 `complete`、`declaredCommentCount`、`stopReason`、`metadata`。
- `DouyinCommentItem.metadata` 可以先承载 `pageIndex`、`source`、`cursor`、`rawId` 等页内信息。

主要差距:

- 当前 `collectAllComments` 是一次性结果, 缺少每页事件和页级 checkpoint。
- 采集依赖 DOM/region 滚动, 虚拟列表、焦点丢失、布局漂移时难以判断真实进度。
- `videoLimit` 已进入输入模型, 但执行器当前实际只打开 `openVideo(0)`。
- 没有一等的 `CommentPage` / `CommentCursorCheckpoint` 模型。
- 停止原因粒度还不够适合恢复和运营排障。

---

## 2. 目标与非目标

### 2.1 目标

1. 把评论采集从「一次滚动大循环」升级为「结构化分页状态机」。
2. 每页采集后立刻持久化评论、发布事件、写 checkpoint。
3. 保持当前 DOM/region 采集可用, 第一阶段不依赖平台 API。
4. 为后续安全受控的 in-page fetch/API 采集留下接口。
5. 支持多视频循环、二级评论、暂停/续跑、看板统计的演进。

### 2.2 非目标

- 不实现平台风控绕过。
- 不自动破解验证码、滑块、登录验证。
- 不引入代理池规避限制。
- 不复制 MediaCrawler 代码或签名实现。
- 不做高频、并发、批量账号抓取能力。
- 不绕过 Tool Guard 做关注、私信、发送等外联动作。

---

## 3. 核心概念

### 3.1 CommentPage

评论页是采集的最小可审计单元。

```java
record CommentPageResult(
    String platform,
    String videoKey,
    int pageIndex,
    String cursor,
    String nextCursor,
    boolean hasMore,
    List<DouyinCommentItem> comments,
    int rawCount,
    int seenNewCount,
    int insertedCount,
    int duplicateCount,
    int failedCount,
    CommentCollectionSource source,
    CommentStopReason stopReason,
    String failureCode,
    String failureMessage,
    Map<String, Object> metadata
) {}
```

字段含义:

| 字段 | 含义 |
|---|---|
| `videoKey` | 视频稳定标识, DOM 阶段可用 URL / title hash, API 阶段用 aweme_id |
| `pageIndex` | 当前视频内页序号, 从 0 开始 |
| `cursor` | 采集前 cursor; DOM 阶段可为空或使用滚动窗口 hash |
| `nextCursor` | 下一页 cursor; DOM 阶段可使用下次滚动窗口 hash / scrollTop |
| `hasMore` | 是否还可能有下一页 |
| `rawCount` | 本页解析出的评论数 |
| `seenNewCount` | 扣除本轮已见 `commentKey` 后的新评论数 |
| `insertedCount` | 本页实际插入数据库的新评论数 |
| `duplicateCount` | 命中唯一键的重复评论数 |
| `failedCount` | 非重复类数据库失败数 |
| `source` | P0 为 `DOM_REGION`; P2 可选 `IN_PAGE_FETCH` |
| `stopReason` | 本页或本轮停止原因 |
| `metadata` | comment count evidence、region fingerprint、scrollTop、declared count 等 |

### 3.2 CommentCursorCheckpoint

checkpoint 是恢复和排障的关键。

```java
record CommentCursorCheckpoint(
    Long taskId,
    Long runId,
    String videoKey,
    int videoIndex,
    int pageIndex,
    String currentCursor,
    String nextCursor,
    int pagesFetched,
    int commentsCollected,
    int uniqueCommentsCollected,
    String seenCommentKeysRef,
    CommentCollectionSource source,
    CommentStopReason stopReason,
    boolean resumeAllowed,
    Map<String, Object> metadata
) {}
```

第一阶段不一定要新建表, 可以先写「可观测 checkpoint」:

- `mate_agent_step.checkpoint_ref`: `lead-comment-checkpoint:<runId>:<videoKey>:<pageIndex>`
- `mate_agent_event.payload_json`: 发布 checkpoint payload
- `mate_lead_comment.metadata_json`: 给每条评论带上页级来源信息

这只能支持排障、审计和后续恢复设计, 还不等于真正断点续跑。真正的 `resumeFromCheckpoint=true` 需要有读取最近 checkpoint、恢复 cursor、恢复 seen comment keys、校验当前视频上下文的路径; 建议放到 P1 页表或持久化 checkpoint 完成后再开启。

### 3.3 StopReason

建议统一枚举:

| StopReason | 含义 |
|---|---|
| `HAS_MORE_FALSE` | 平台或页面证据显示没有更多 |
| `MAX_COMMENTS_REACHED` | 达到单视频评论上限 |
| `MAX_PAGES_REACHED` | 达到单视频页数上限 |
| `DECLARED_COUNT_REACHED` | 已采集数达到页面声明评论数 |
| `NO_NEW_COMMENTS` | 连续若干页没有新评论 |
| `DOM_FALLBACK_EXHAUSTED` | DOM/region 滚动兜底已穷尽 |
| `REGION_LOST` | 评论区区域丢失或漂移 |
| `VIDEO_CHANGED` | 滚动或页面变化导致视频上下文变化 |
| `AUTH_EXPIRED` | 登录态失效 |
| `CAPTCHA_OR_VERIFY` | 出现验证码、滑块、安全验证 |
| `RATE_LIMITED` | 请求或页面提示被限流 |
| `PROTECTION_LIMIT` | 疑似触发平台保护, 主动停止 |
| `NETWORK_ERROR` | 网络错误 |
| `USER_CANCELLED` | 用户取消任务 |
| `UNKNOWN` | 未分类停止 |

遇到 `CAPTCHA_OR_VERIFY`、`RATE_LIMITED`、`PROTECTION_LIMIT`、`AUTH_EXPIRED` 时, 应停止采集并上报, 不继续尝试规避。

### 3.4 旧 stopReason 兼容映射

当前实现已经会产出一批字符串 stopReason, P0 不能直接替换, 否则会影响 `DouyinLeadAcquisitionTool` 的 reporting guidance 和既有 timeline 判断。建议新增标准枚举时同时保留 `legacyStopReason` 到 metadata, 并按下表映射:

| 当前 stopReason | 标准 StopReason | 兼容要求 |
|---|---|---|
| `END_OF_LIST` | `HAS_MORE_FALSE` | 保留 `bottomConfirmed=true`; reporting guidance 仍可识别到底 |
| `COMMENT_EXTRACTION_NOT_ADVANCING` | `NO_NEW_COMMENTS` | 记录连续无新增页数 |
| `COMMENT_SCROLL_STALLED` | `DOM_FALLBACK_EXHAUSTED` | 记录 scroll evidence |
| `COMMENT_PANEL_LOST_*` | `REGION_LOST` | 保留原始后缀到 `failureCode` 或 metadata |
| `VIDEO_CHANGED_DURING_*` | `VIDEO_CHANGED` | 立即停止当前视频采集 |
| `INTERRUPTED` | `USER_CANCELLED` | 如果不是用户取消, 记录为 `UNKNOWN` 并带原始异常 |
| `PROTECTION_LIMIT` | `PROTECTION_LIMIT` | `resumeAllowed=false`, 不 fallback |

P0 汇总事件应同时输出 `stopReason` 和 `legacyStopReason`, 至少一个版本周期内保持兼容。

---

## 4. 目标架构

```text
DouyinLeadAcquisitionExecutor
  -> DouyinVideoIterator
      -> open/search/sort/pick video
  -> DouyinStructuredCommentCollector
      -> choose page collector by mode
      -> collect page
      -> save page comments idempotently
      -> publish page event
      -> write checkpoint
      -> decide next page / stop
  -> CommentMatcher
  -> engagement steps
```

### 4.1 Collector 分层

| 组件 | 责任 |
|---|---|
| `DouyinStructuredCommentCollector` | 分页状态机, 不关心具体采集来源 |
| `DouyinCommentPageCollector` | 页采集接口 |
| `DouyinDomCommentPageCollector` | 现有 DOM/region extract + scroll 的分页化封装 |
| `DouyinInPageFetchCommentCollector` | 后续可选: 在抖音页面上下文内低频 fetch 评论页 |
| `CommentPagePersistenceService` | 页事件、评论幂等保存、checkpoint 写入 |
| `CommentCollectionPolicy` | 上限、间隔、失败后是否恢复、停止原因判定 |

接口草图:

```java
interface DouyinCommentPageCollector {
    CommentCollectionSource source();

    CommentPageResult collectPage(CommentPageRequest request);
}
```

```java
record CommentPageRequest(
    Long runId,
    Long taskId,
    String videoKey,
    int videoIndex,
    int pageIndex,
    String cursor,
    DouyinBrowserAdapter.RegionInfo region,
    CommentCollectionOptions options,
    Set<String> seenCommentKeys
) {}
```

### 4.2 CollectionMode

新增输入参数建议:

| 参数 | 默认值 | 说明 |
|---|---|---|
| `collectionMode` | `dom_region` | P0 默认只启用 `dom_region`; P2 完成授权、Tool Guard 和管理员开关后才允许 `auto` / `in_page_fetch` |
| `maxCommentsPerVideo` | `300` | 单视频评论上限 |
| `maxPagesPerVideo` | `40` | 单视频页数上限 |
| `commentPageSize` | `20` | API/fetch 页大小; DOM 阶段仅用于事件口径 |
| `includeReplies` | `false` | 是否采集二级评论 |
| `crawlIntervalMs` | `800` | 页间最小间隔, 不做高频采集 |
| `resumeFromCheckpoint` | `false` | P0 只写可观测 checkpoint; P1 有恢复路径后再开启 |

`DouyinLeadAcquisitionInput` 当前已有 `keyword/sort/videoLimit/commentMatchRule/dmDraft/sendDm/engage`, 后续可以在保持兼容构造器的前提下增加这些字段。

注意: `auto` 不能在 P0 默认开启。`in_page_fetch` 必须同时满足管理员开启、用户明确授权当前登录账号/会话、低频上限生效、保护类 stopReason 不自动恢复。

P0 第一轮不建议把这些参数暴露成公共 REST/tool/skill schema 入参; 先使用内部默认值完成“分页可观测 + 汇总兼容”。等 P0 稳定后, P1/P2 再同步 REST request、工具参数、skill schema 和前端入口。

---

## 5. P0: 先把现有 DOM 采集分页化

P0 不引入平台 API, 只把当前 `collectAllComments(region)` 变成有页事件和 checkpoint 的状态机。

P0 第一轮收窄为:

- 不新增数据库表。
- 不新增 Extension action。
- 不新增公开输入参数。
- 不改前端和 skill schema。
- 不改变 `complete` / `stopReason` 的既有含义。
- 不实现真正断点续跑, 只写可观测 checkpoint。
- 目标只做“分页可观测 + 每页幂等保存 + 汇总事件兼容”。

### 5.1 DOM 页的定义

DOM 阶段没有真实 API cursor, 可以用「抽取窗口」当作页:

```text
page 0: 打开评论区后的首次 extract_region
page 1: scroll_region 一次后 extract_region
page 2: 再滚动一次后 extract_region
...
```

DOM cursor 可以定义为:

```text
dom:<regionFingerprint>:<scrollAttempt>:<visibleCommentHash>
```

`nextCursor` 可以是滚动后的 `scrollTop` / `windowEvidence` / `visibleCommentHash`。这不等于平台 cursor, 但足够做:

- 事件追踪
- 去重
- 停止原因判断
- 排障复盘
- 未来迁移到真实 cursor 时保持同一接口

### 5.2 P0 改造点

后端:

- 新增 `CommentPageResult`、`CommentPageRequest`、`CommentCollectionOptions`、`CommentStopReason`、`CommentCollectionSource`。
- 新增 `DouyinStructuredCommentCollector`, 内部先只接 `DouyinDomCommentPageCollector`。
- P0 推荐由 `DouyinLeadAcquisitionExecutor` 直接注入并调用 `DouyinStructuredCommentCollector`, 因为页级事件、checkpoint、每页保存需要 `runId/taskId/options` 上下文。
- `ExtensionDouyinBrowserAdapter.collectAllComments(region)` 可以保留为兼容 wrapper, 但不应承载页级持久化和事件职责; 更稳的做法是让 browser adapter 只提供 extract/scroll/open 等页面动作。
- `CommentCollectionResult.metadata` 增加:
  - `source`
  - `pagesFetched`
  - `lastCursor`
  - `lastNextCursor`
  - `uniqueCommentsCollected`
  - `stopReasonCode`
  - `legacyStopReason`
- `DouyinLeadAcquisitionExecutor` 继续消费 `CommentCollectionResult`, 最小化外部改动。
- `LeadPersistenceService.saveComments(...)` 继续作为评论保存入口; P0 应新增 `saveCommentsFromPage(...)` 包装并返回 `insertedCount/duplicateCount/failedCount`。
- `saveCommentsFromPage(...)` 只能把唯一键冲突计为 duplicate; 不能像当前 `saveComments` 一样把所有 `RuntimeException` 都当成重复插入吞掉, 否则页级新增数和失败审计会失真。

不要把完整 `pageEvents` 数组塞进 `CommentCollectionResult.metadata`: 执行器会把 metadata 合并进最终 `lead.comments.collected`, 工具返回也会携带事件列表。完整页明细应单独进入 `mate_agent_event` timeline, 汇总事件只保留紧凑摘要字段。

页级新增数口径建议:

- `seenNewCount`: 本轮内扣除 `seenCommentKeys` 后的新评论数。
- `insertedCount`: 本页实际插入数据库的新评论数。
- `duplicateCount`: 命中 `(task_id, comment_key)` 唯一键的重复数。
- `failedCount`: 非重复类数据库错误数量; 这类错误不能静默吞掉。

事件:

```json
{
  "eventType": "lead.comments.page_collected",
  "payload": {
    "taskId": "string-id",
    "videoKey": "string",
    "videoIndex": 0,
    "pageIndex": 3,
    "cursor": "dom:...",
    "nextCursor": "dom:...",
    "hasMore": true,
    "rawCount": 18,
    "seenNewCount": 7,
    "insertedCount": 6,
    "duplicateCount": 1,
    "failedCount": 0,
    "totalUniqueComments": 43,
    "source": "DOM_REGION",
    "stopReason": null,
    "elapsedMs": 642
  }
}
```

```json
{
  "eventType": "lead.comments.collection_checkpointed",
  "payload": {
    "taskId": "string-id",
    "videoKey": "string",
    "pageIndex": 3,
    "nextCursor": "dom:...",
    "commentsCollected": 43,
    "resumeAllowed": false
  }
}
```

停止策略:

- 达到 `maxCommentsPerVideo` -> `MAX_COMMENTS_REACHED`
- 达到 `maxPagesPerVideo` -> `MAX_PAGES_REACHED`
- 连续 N 页 `seenNewCount=0` -> `NO_NEW_COMMENTS`
- 页面声明评论数已满足 -> `DECLARED_COUNT_REACHED`
- 评论区丢失 -> `REGION_LOST`
- 出现登录/验证/限流证据 -> `AUTH_EXPIRED` / `CAPTCHA_OR_VERIFY` / `RATE_LIMITED` / `PROTECTION_LIMIT`

P0 验收:

- 一次任务的 timeline 能看到多条 `lead.comments.page_collected`。
- 每页 payload 能解释“为什么继续/为什么停止”。
- 重跑同一任务不会重复插入同一评论。
- `lead.comments.collected` 汇总仍保持兼容。
- 现有工具调用返回里的 reporting guidance 能继续读取 `complete/stopReason/commentsCollected`。
- `bottomConfirmed` 仍只由真实到底证据触发, 不能因为达到上限而误报完整。

---

## 6. P1: 页表与多视频循环

P1 引入一等页表, 并把 `videoLimit` 从输入真正落到执行器。

### 6.1 可选页表

如果只做 P0, 事件流已经够用; 如果要支持可靠续跑、看板和页级排障, 建议加表:

```sql
CREATE TABLE mate_lead_comment_page (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    task_id             BIGINT       NOT NULL,
    run_id              BIGINT       NOT NULL,
    platform            VARCHAR(64)  NOT NULL,
    video_key           VARCHAR(256) NOT NULL,
    video_index         INT          NOT NULL DEFAULT 0,
    page_index          INT          NOT NULL,
    cursor              VARCHAR(512),
    next_cursor         VARCHAR(512),
    has_more            BOOLEAN      NOT NULL DEFAULT FALSE,
    source              VARCHAR(64)  NOT NULL,
    raw_count           INT          NOT NULL DEFAULT 0,
    new_count           INT          NOT NULL DEFAULT 0,
    total_unique_count  INT          NOT NULL DEFAULT 0,
    status              VARCHAR(32)  NOT NULL,
    stop_reason         VARCHAR(64),
    failure_code        VARCHAR(128),
    failure_message     VARCHAR(2048),
    metadata_json       CLOB,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

索引:

- `(task_id, video_key, page_index)` 唯一或普通索引
- `(run_id, create_time)`
- `(task_id, source, status)`

注意: 若正式实现迁移, 必须同时新增 H2 和 MySQL 两份 `Vn__*.sql`, 并处理 MySQL 的 `TEXT` / `BOOLEAN` 差异。

### 6.2 多视频循环

当前执行器路径是 `openVideo(0)`, P1 应改为:

```text
build DouyinVideoIterator from sorted search results
for videoIndex in 0..<input.videoLimit:
  iterator.open(videoIndex)
  publish lead.video.opened
  openComments
  detectCommentRegion
  collect comments by structured collector
  save/match
  if no more search results -> stop
```

不能只写 `for` 循环然后调用现有 `openVideo(videoIndex)`: 当前适配器在已经处于视频页时可能复用当前视频上下文, 不保证回到搜索结果并打开第 N 条候选。P1 需要先实现 `DouyinVideoIterator`, 明确负责:

- 保存或重新获取排序后的搜索候选列表。
- 打开指定 index 的候选视频。
- 校验 `videoKey/url/title` 与上一视频不同。
- 当前页面丢失时能回到搜索结果并恢复候选上下文。
- 无更多候选时输出明确 stopReason。

每个视频独立 checkpoint:

```text
lead-comment-checkpoint:<runId>:video:<videoIndex>:page:<pageIndex>
```

汇总事件新增:

- `lead.video.collection_started`
- `lead.video.collection_completed`
- `lead.video.collection_failed`

多视频聚合到 `lead.comments.collected`:

```json
{
  "videosProcessed": 3,
  "commentsCollected": 186,
  "pagesFetched": 27,
  "complete": true,
  "videoSummaries": [
    {"videoIndex": 0, "videoKey": "...", "comments": 80, "pages": 10, "stopReason": "HAS_MORE_FALSE"},
    {"videoIndex": 1, "videoKey": "...", "comments": 74, "pages": 9, "stopReason": "MAX_COMMENTS_REACHED"},
    {"videoIndex": 2, "videoKey": "...", "comments": 32, "pages": 8, "stopReason": "NO_NEW_COMMENTS"}
  ]
}
```

---

## 7. P2: 受控 in-page fetch 采集

P2 才考虑把 MediaCrawler 的“浏览器登录态 + 分页接口”思想落到 MateClaw。

### 7.1 原则

- 优先在用户已打开、已登录的抖音页面上下文内执行低频 fetch。
- 必须由用户明确授权使用当前登录账号/会话。
- fetch 只用于读取当前用户可正常浏览的公开视频评论, 不采集私信、非公开内容或平台未展示给用户的数据。
- 不实现签名绕过; 如果平台要求不可获取的签名或验证, 返回 `PROTECTION_LIMIT` / `CAPTCHA_OR_VERIFY`。
- 不自动解验证码, 不换代理继续打。
- 严格受 `maxCommentsPerVideo`、`maxPagesPerVideo`、`crawlIntervalMs` 控制。

### 7.2 能力形态

扩展侧可选新增 domain-specific action:

```text
douyin_fetch_comment_page
```

输入:

```json
{
  "videoKey": "aweme_id or current video",
  "cursor": "0",
  "count": 20,
  "includeReplies": false
}
```

输出:

```json
{
  "ok": true,
  "videoKey": "...",
  "cursor": "0",
  "nextCursor": "20",
  "hasMore": true,
  "comments": [],
  "source": "IN_PAGE_FETCH",
  "failureCode": null,
  "failureMessage": null
}
```

后端 collector:

```text
auto mode:
  try IN_PAGE_FETCH for page 0
    success -> continue fetch pages
    protection/auth/rate-limit -> stop and surface reason
    unsupported/feature_disabled -> fallback DOM_REGION
  DOM_REGION remains fallback and author navigation evidence source
```

fallback 只允许发生在 `UNSUPPORTED`、`FEATURE_DISABLED` 这类非风控原因上。若原因是 `AUTH_EXPIRED`、`CAPTCHA_OR_VERIFY`、`RATE_LIMITED`、`PROTECTION_LIMIT`, 必须停止并等待用户处理, 不能切到 DOM 路径继续采集。

### 7.3 为什么不是直接后端 API client

直接后端 API client 容易把 MateClaw 变成“独立爬虫服务”, 也更容易走向签名、代理、反检测等不该借的方向。in-page fetch 更符合 MateClaw 当前 Browser Agent 架构:

- 用户浏览器承载登录态和页面上下文。
- Server 保持采集状态机、事件和持久化。
- Extension 只执行受控、低频、可失败上报的页面内动作。
- 出现保护信号时停止, 而不是增强规避能力。

---

## 8. P3: 二级评论与看板

二级评论可以复用同一套页模型:

```text
parentCommentKey = null        -> 主评论
parentCommentKey = commentKey  -> 二级评论
cursor scope = videoKey + parentCommentKey
```

新增事件:

- `lead.comments.replies.page_collected`
- `lead.comments.replies.collection_completed`

看板可直接聚合:

- 每个视频采集页数
- 每页新增评论数
- 完整度
- 停止原因分布
- DOM vs in-page fetch 成功率
- 平均每页耗时
- 命中评论来源页

---

## 9. 文件级改造清单

### 9.1 后端 Java

优先新增:

| 文件 | 动作 |
|---|---|
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/model/CommentPageResult.java` | 新增页结果模型 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/model/CommentPageRequest.java` | 新增页请求模型 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/model/CommentCollectionOptions.java` | 新增采集参数 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/model/CommentCollectionSource.java` | 新增来源枚举 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/model/CommentStopReason.java` | 新增停止原因枚举 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/collect/DouyinCommentPageCollector.java` | 新增页采集接口 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/collect/DouyinDomCommentPageCollector.java` | 封装当前 region extract + scroll 为页 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/collect/DouyinStructuredCommentCollector.java` | 分页状态机 |

需要调整:

| 文件 | 动作 |
|---|---|
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/model/DouyinLeadAcquisitionInput.java` | 增加采集参数, 保持兼容构造器 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/model/CommentCollectionResult.java` | 增加页摘要或通过 metadata 承载 P0 页信息 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/model/DouyinCommentItem.java` | metadata 写入 `pageIndex/source/rawId/cursor` |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/DouyinLeadAcquisitionExecutor.java` | P0 直接调用 structured collector; P1 做多视频循环 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/store/LeadPersistenceService.java` | 增加 `saveCommentsFromPage` / 可选 `saveCommentPage` |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/browser/ExtensionDouyinBrowserAdapter.java` | 保持页面动作职责; 不承载页级持久化 |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/api/DouyinLeadAcquisitionStartRequest.java` | API 入参增加分页参数 |
| `mateclaw-server/src/main/java/vip/mate/tool/builtin/DouyinLeadAcquisitionTool.java` | 工具入参和 reporting guidance 增加页级摘要, 兼容 `END_OF_LIST` |

### 9.2 Extension / Browser Edge

P0 可不新增 action, 复用:

- `detect_region.ts`
- `extract_region.ts`
- `scroll_region.ts`

P2 可新增:

| 文件 | 动作 |
|---|---|
| `mateclaw-extension/src/sw/action/handlers/douyin_fetch_comment_page.ts` | 页面上下文内低频 fetch 当前视频评论页 |
| `mateclaw-extension/src/sw/action/handlers/index.ts` | 注册 action |
| `mateclaw-server/src/main/java/vip/mate/lead/douyin/browser/DouyinBrowserAdapter.java` | 可选新增 `fetchCommentPage` |
| `ExtensionDouyinBrowserAdapter.java` | 实现 action 调用和失败码映射 |

### 9.3 数据库

P0 不强制迁移。

P1 如加 `mate_lead_comment_page`, 新增:

- `mateclaw-server/src/main/resources/db/migration/h2/V134__lead_comment_pages.sql`
- `mateclaw-server/src/main/resources/db/migration/mysql/V134__lead_comment_pages.sql`

H2/MySQL 必须同步。

---

## 10. 事件契约

### 10.1 `lead.comments.page_collected`

每页采集后发布。

```json
{
  "taskId": "10001",
  "runId": "90001",
  "videoKey": "aweme:...",
  "videoIndex": 0,
  "pageIndex": 4,
  "cursor": "80",
  "nextCursor": "100",
  "hasMore": true,
  "rawCount": 20,
  "seenNewCount": 18,
  "insertedCount": 18,
  "duplicateCount": 0,
  "totalUniqueComments": 91,
  "source": "IN_PAGE_FETCH",
  "stopReason": null,
  "failureCode": null,
  "elapsedMs": 731
}
```

### 10.2 `lead.comments.collection_checkpointed`

每页落 checkpoint 后发布。

```json
{
  "checkpointRef": "lead-comment-checkpoint:90001:video:0:page:4",
  "taskId": "10001",
  "runId": "90001",
  "videoKey": "aweme:...",
  "videoIndex": 0,
  "pageIndex": 4,
  "nextCursor": "100",
  "commentsCollected": 91,
  "resumeAllowed": true
}
```

当 `stopReason` 属于 `AUTH_EXPIRED`、`CAPTCHA_OR_VERIFY`、`RATE_LIMITED`、`PROTECTION_LIMIT` 时, `resumeAllowed` 必须为 `false`, 需要用户重新登录、处理验证、重新授权或审批后才能恢复。

### 10.3 `lead.comments.collection_fallback_started`

当 `auto` 模式从 in-page fetch 切换到 DOM 兜底时发布。

只允许 `UNSUPPORTED`、`FEATURE_DISABLED` 等非风控原因触发 fallback; 保护类原因必须停止。

```json
{
  "taskId": "10001",
  "runId": "90001",
  "videoKey": "aweme:...",
  "from": "IN_PAGE_FETCH",
  "to": "DOM_REGION",
  "reason": "UNSUPPORTED"
}
```

### 10.4 `lead.comments.collected`

保持现有汇总事件兼容, 增加页级摘要:

```json
{
  "commentsCollected": 91,
  "declaredCommentCount": 120,
  "complete": false,
  "scrollAttempts": 8,
  "stopReason": "MAX_COMMENTS_REACHED",
  "pagesFetched": 5,
  "source": "DOM_REGION",
  "stopReasonCode": "MAX_COMMENTS_REACHED",
  "legacyStopReason": "COMMENT_SCROLL_STALLED",
  "lastCursor": "dom:...",
  "lastNextCursor": "dom:...",
  "collectionCoverage": 0.758
}
```

---

## 11. 测试计划

### 11.1 Java 单元测试

新增或调整:

- `DouyinStructuredCommentCollectorTest`
  - 按页采集直到 `HAS_MORE_FALSE`
  - 达到 `MAX_COMMENTS_REACHED` 停止
  - 连续无新增 -> `NO_NEW_COMMENTS`
  - `CAPTCHA_OR_VERIFY` / `RATE_LIMITED` 立即停止且不 fallback 规避
  - 保护类 stopReason 下 `resumeAllowed=false`
  - `collectionMode=auto` 在未授权或 feature flag 未开启时不会触发 fetch
  - checkpoint payload 包含 nextCursor 和累计数
- `DouyinDomCommentPageCollectorTest`
  - DOM 窗口 hash 生成稳定 cursor
  - region lost 映射为 `REGION_LOST`
- `LeadPersistenceServiceTest`
  - 同页重复评论不重复写入
  - 跨页重复评论不重复写入
  - `saveCommentsFromPage` 返回 `insertedCount/duplicateCount/failedCount`
  - 非唯一键冲突的数据库异常不能被当作重复吞掉
  - metadata 保留 `pageIndex/source/cursor`
- `DouyinLeadAcquisitionExecutorTest`
  - P0: 汇总事件兼容
  - P0: 页事件顺序正确, 最终 `lead.comments.collected` 不携带完整 pageEvents 数组
  - P0: `complete=false` 仍按现有策略停止后续匹配/互动
  - Tool guidance: `pagesFetched/source` 出现时仍保持 partial 限制, `bottomConfirmed` 只在真实到底证据下为 true
  - P1: `videoLimit=3` 会打开 3 个视频或在无结果时停止

### 11.2 Extension 测试

P0:

- `extract_region` 评论抽取字段稳定。
- `scroll_region` 返回足够的滚动证据。

P2:

- `douyin_fetch_comment_page` 成功页、unsupported、auth expired、rate limited、verify page 的输出映射。
- 不在 action 内做自动重试风控绕过。
- 不提供代理轮换、签名破解、验证码自动破解相关配置。

### 11.3 真机冒烟

只做低频、小样本:

1. 打开抖音, 搜索固定关键词, 采集 1 个视频。
2. timeline 出现多条 page event。
3. 评论重复率可解释, `seenNewCount` 递减到连续无新增时能停止。
4. 出现验证/登录失效时任务停止并显示具体 stopReason。
5. 不要求绕过平台保护。

---

## 12. 分阶段交付建议

### P0.1: 文档与模型

- 新增本文档。
- 增加模型和枚举。
- 不改数据库。
- 不新增公共 REST/tool/skill schema 入参。

### P0.2: DOM 采集分页化

- `DouyinLeadAcquisitionExecutor` 直接调用 structured collector。
- browser adapter 继续提供 extract/scroll/open 页面动作; `collectAllComments(region)` 只作为兼容 wrapper。
- 每次 extract + scroll 形成一页。
- 发布 `lead.comments.page_collected`。
- `lead.comments.collected` 保持兼容。

### P0.3: checkpoint 与 reporting

- 每页写 `collection_checkpointed`。
- `StepCloseRequest.checkpointRef` 写最后页可观测 checkpoint。
- 工具返回说明增加 `pagesFetched/stopReason/source`。
- 不默认开启 `resumeFromCheckpoint`; 只在 P1 有持久化恢复路径后开启。

### P1: 多视频与页表

- 实现 `DouyinVideoIterator`, 再让执行器接入 `videoLimit` 循环。
- 可选新增 `mate_lead_comment_page`。
- 前端/查询接口可展示页级 timeline。

### P2: in-page fetch

- 新增受控页面内 fetch action。
- 管理员开启且用户授权后, `collectionMode=auto` 才可优先 fetch; unsupported/feature_disabled 时 fallback DOM。
- protection/auth/rate-limit 不做规避, 直接 stop。

### P3: 二级评论与数据面板

- `includeReplies` 支持二级评论。
- 数据面板展示页数、评论增量、停止原因、命中来源页。

---

## 13. 推荐优先级

优先做 P0, 原因:

- 不碰平台 API, 风险最低。
- 不需要数据库迁移, 改动范围可控。
- 能立刻提升可观测性和排障能力。
- 为 P1 多视频、P2 in-page fetch 共用同一套状态机。

推荐第一轮实现范围:

1. 新增模型和 `DouyinStructuredCommentCollector`。
2. 用现有 `extract_region + scroll_region` 实现 DOM 页。
3. 每页发布 `lead.comments.page_collected`。
4. `CommentCollectionResult.metadata` 增加页摘要。
5. 不新增表, 不新增 Extension action。

这一轮完成后, MateClaw 的评论采集即使仍是 DOM 来源, 也已经从“黑盒滚到底”变成了“可分页、可审计、可恢复演进”的结构化采集。
