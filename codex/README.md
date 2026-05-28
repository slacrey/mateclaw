# Codex 并行任务包 — MateClaw Browser Agent Phase 1 + Phase 2 prep

本目录里每个 `NN-*.md` 文件都是一个 **独立完整、可单独喂给 Codex 的 prompt**。
打开多个 Codex 对话，每个对话粘贴一个文件的全文，让 Codex 产出代码 / 文档，
然后你把产出落到仓库对应路径并提交。

## 派发原则

- **互不冲突**：每个任务的文件路径互相独立，可以全部并行。
- **不和当前正在跑的 sub-agent 冲突**：
  - A-stream（Java）正在 `mateclaw-server/src/{main,test}/java/vip/mate/browser/edge/`（不含 `action/` 子包）+ `WebSocketConfig.java`
  - B-stream finisher 正在 `mateclaw-browser-bridge/src/internal/nm/` 和 `runner/`
- **TDD 强约束**：所有任务都要求"先写失败测试 → 实现 → 测试通过"。Codex 产出代码 + 测试 + 运行步骤说明。
- **Conventional Commits + Co-Authored-By trailer**：每个产出独立 commit，trailer 用：
  ```
  Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
  ```

## 任务清单

| # | 文件 | 目标 | 优先级 | 互斥分组 |
|---|---|---|---|---|
| 01 | `01-install-scripts.md` | D1: Chrome Native Messaging manifest + 3 个 OS install 脚本 | **MUST**（Phase 1 收尾） | 独立 |
| 02 | `02-audit-script.md` | `scripts/audit-phase-1.sh`：最终审计/CI 验证脚本 | **MUST**（最终审计） | 独立 |
| 03 | `03-phase2-action-payload.md` | Phase 2 P2: Java sealed `ActionPayload` + 6 个子类型 + 测试 | HIGH（Phase 2 起跑） | 独立 |
| 04 | `04-phase2-tab-ref.md` | Phase 2 P2: Java sealed `TabRef` + Jackson 自定义 serde + 测试 | HIGH（Phase 2 起跑） | 独立 |
| 05 | `05-threat-model.md` | `docs/security/browser-agent-threat-model.md`：STRIDE 威胁模型 | MEDIUM（Phase 3 安全审查需要） | 独立 |
| 06 | `06-perf-bench.md` | TS perf 基准：envelope 编解码 + NM frame 编解码 | MEDIUM（Phase 2 性能基线） | 独立 |

## 推荐派发顺序

**第一波（任意时刻可派）**：01、02、05、06 — 互不依赖任何还没完成的 agent。
**第二波（A-stream 完成后再派）**：03、04 — 它们会在 `vip.mate.browser.edge.action/` 创建新子包，A-stream 完成 / 合并后冲突风险为 0。

如果 Codex 一次产出某个任务太长会被截断，让它分段给你；每段都自己跑测试再提交一次。

## 编排者侧（你 + 我）该做的

1. 你复制 prompt 给 Codex，拿到产出
2. 落到本地仓库对应路径
3. 跑测试确认绿
4. `git add` + `git commit`（注意 trailer）
5. 跟我说"NN 完成"，我会在最终审计里把它纳入验证清单

## 一次完整往返示例

```
你: [复制 codex/01-install-scripts.md 全文给 Codex]
Codex: [输出 4 个文件的完整内容 + 运行说明]
你: [粘贴 4 个文件到 mateclaw-browser-bridge/install/*]
你: cd mateclaw-browser-bridge/install && powershell -File install-windows.ps1 -BridgePath ... -ExtensionId ...
你: git add mateclaw-browser-bridge/install && git commit -m "feat(install): ..."
你 → 我: "01 完成"
```
