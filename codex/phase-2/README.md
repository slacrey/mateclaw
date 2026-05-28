# Codex Phase 2 任务包 — Wave 1（5 流并行）

本目录扩展上层 `codex/README.md` 的 Phase 1 框架，专用于 Phase 2 计划的并行实施。

## 当前波次：Wave 1（依赖 P1/P2，已合并到 `feat/browser-foundation`）

| # | 文件 | 目标 | 互斥分组 |
|---|---|---|---|
| 07 | `07-p3-action-execution-service.md` | P3: `ActionExecutionService` + cancel CAS + `indicator.stop_clicked` 路由 | Java — 与 Codex 08/09 不冲突 |
| 08 | `08-b1-debugger-lifecycle.md` | B1: chrome.debugger attach/detach + `SessionDetachedError` + devtools_open 检测 | TS extension — 与 Codex 09 不冲突 |
| 09 | `09-d1-tab-group-manager.md` | D1: `TabGroupManager` + `event.tab.closed` / `event.page.navigated` 发射 | TS extension — 与 Codex 08 不冲突 |

并行同时运行的 Claude sub-agents（**不要碰这两个目录**）：
- **Sub-agent A** in worktree `feat/wave1-b9-windmouse`: `mateclaw-extension/src/lib/windmouse.ts`
- **Sub-agent B** in worktree `feat/wave1-c1-a11y-cs`: `mateclaw-extension/src/content/a11y-tree.ts` + `manifest.json` content_scripts 条目

## TDD + 提交规范（与 Phase 1 一致）

1. 先写失败测试（test 文件先于实现）
2. 跑测试，确认 fail（必须截屏式输出失败原因）
3. 实现
4. 再跑测试，确认 pass
5. `git add <specific files>` + `git commit -m "..."` with trailer:
   ```
   Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
   ```

## 派发顺序

Wave 1 的 3 个 Codex 任务**全部互不冲突**：可同时派 3 个 Codex 对话。

## 与 Phase 1 codex/ 的区别

- 这一波运行在 `feat/browser-foundation` 分支上（Phase 1 已合并 + Wave 0 已 commit `3a75f1b7` + `dd7a6fa5`）。
- 协议契约固定到 v1.1（spec 文档在 `docs/specs/edge-protocol.md`）。
- 所有 `ActionRequest` / `TabRef` / `EdgeMessageKind` 类型已就位 — 直接 import 即可。

## 完成回报

每个任务做完后告诉我"Codex NN 完成"，我会在 Wave 1 final merge 时把它纳入审计清单。
