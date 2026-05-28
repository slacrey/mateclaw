# Codex Task 02 — Final Audit Script

> 你（Codex）要交付一个**最终审计脚本** `scripts/audit-phase-1.sh`，让项目维护
> 者在 Phase 1 所有 sub-agent 合并完成后，**一行命令就能跑完三个测试套件 +
> grep 所有 Codex 关键不变量 + 输出结构化报告**。

## 项目背景

MateClaw Browser Agent Phase 1 有三个工作流：
- **A 流（Java）**：`mateclaw-server/`，包名 `vip.mate.browser.*`，测试用 `mvn test -Dtest='vip.mate.browser.**'`
- **B 流（TypeScript/Node）**：`mateclaw-browser-bridge/`，测试用 `pnpm test`，构建 `pnpm build`
- **C 流（TypeScript/Browser）**：`mateclaw-extension/`，测试用 `pnpm test`，构建 `pnpm build`

每个工作流都有 Codex 早期审计列出的"不变量"必须在最终代码里成立。Phase 1
v1.1 / v1.2 的关键不变量（用 grep 可机械验证的）：

1. **untrusted-event ban**：扩展不能用 `.click()` 或
   `dispatchEvent(new (Mouse|Keyboard)Event)`（这两者带 `isTrusted=false`，被反爬
   识破）。grep 范围：`mateclaw-extension/src/`。结果应为 **0 行**。
2. **session_id 拥有规则**：Native Host 才能给 envelope 盖 `session_id`，扩展端
   永远写空字符串。grep `session_id: ['\"](sess|user|alice|bob)` 在
   `mateclaw-extension/src/` 应为 **0 行**（测试 fixture 除外，但 v1.2 plan 要
   求测试也用空串）。
3. **register CAS 不变量**：Java 注册表用 `subjectToSession.compute(...)` 做原子
   替换，禁止单参 `subjectToSession.put(...)` 后再 `byId.remove(...)` 的非原子
   两步。grep
   `subjectToSession\.put\(` 在 `mateclaw-server/src/main/java/vip/mate/browser/edge/session/BrowserSessionRegistry.java`
   应为 **0 行**。
4. **真实 Auth API**：A 流必须用 `AuthService.parseClaims` 和
   `PersonalAccessTokenService.findActiveByPlaintext`，**不能**自己加
   `JwtService.parseUserId` 或 `validateAndGetUserId` 包装。grep
   `JwtService\.parseUserId\|validateAndGetUserId` 应为 **0 行**。
5. **单 reader 不变量**（B 流）：`mateclaw-browser-bridge/src/` 内只能有
   **一处** `\.on\(['\"]message['\"]` 注册（在 `Client` 内 readLoop 里）。多于
   一处 = bug。
6. **ErrHeartbeatTimeout 导出**（B 流）：必须有
   `export class HeartbeatTimeoutError` 或 `export const HeartbeatTimeoutError`
   或同名 `Error` 子类，在 `mateclaw-browser-bridge/src/internal/edge/client.ts`
   能 grep 到。
7. **prefers-reduced-motion**（C 流）：`mateclaw-extension/src/` 内 visual
   indicator 代码（如果 C5 引入了 phantom cursor / glow border）必须包含
   `@media (prefers-reduced-motion: reduce)`。Phase 1 C5 只有 sidepanel UI，没
   visual indicator —— 所以这条 grep 应该 **0 命中也行**，但如果命中至少 2
   次说明 visual 系统已开始。这是 Phase 2 强制的，Phase 1 可以"0 命中 = N/A"。

## 你要交付的文件

### `scripts/audit-phase-1.sh`

POSIX 兼容 shell（要在 Windows Git Bash、macOS、Linux 都能跑），约束：

- shebang `#!/usr/bin/env bash`
- `set -uo pipefail`（不要用 `-e`，单测套件 fail 时我们想继续跑后面的而不是立刻死）
- 头部一段 ASCII banner 说明这是 Phase 1 audit
- **彩色输出**：用 ANSI escape，但通过一个 `color()` 函数封装，便于 `NO_COLOR=1` 环境变量关掉
- **可选参数 `--quick`**：跳过 mvn test（耗时长），只跑 pnpm tests + grep
- 主流程分四个 section，每个 section 头部打印 `=== section name ===`，结尾打印 `PASS` / `FAIL`：
  1. **Test Suite Status**：
     - `cd mateclaw-server && mvn -q test -Dtest='vip.mate.browser.**'`，捕获 exit code
     - `cd mateclaw-browser-bridge && pnpm test`
     - `cd mateclaw-extension && pnpm test`
  2. **Build Sanity**：
     - `cd mateclaw-browser-bridge && pnpm build`，确认 `dist/cmd/bridge.js` 存在
     - `cd mateclaw-extension && pnpm build`，确认 `dist/manifest.json` `dist/sidepanel.html` `dist/service-worker.js` 都存在
  3. **Codex Invariants Grep**：把上面 7 个不变量逐条 grep，命中表 = 行数 0 时 PASS，否则 FAIL 并打印命中行（最多 5 行示例）
  4. **Summary**：四行报表（test/build/invariants/overall），最后一行 `verdict: GREEN | YELLOW | RED`：
     - GREEN：所有 section PASS
     - YELLOW：tests PASS 但 invariants 至少一个 fail
     - RED：tests 或 build fail
- exit code：GREEN=0，YELLOW=1，RED=2

### 行为细节

- 每个 grep 都用 `grep -RIn --include=...` 限定文件类型（`.java` / `.ts` / `.vue`），避免扫到 `node_modules/` / `target/` / `.git/` / `dist/`
- 显式 `--exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist --exclude-dir=.git`
- 不要依赖 `gnu sed`/`gawk` —— BSD sed 也得能跑（即 macOS）
- 不要假设 `mvn` 或 `pnpm` 在 PATH 里 —— 缺失时打印 WARN 并把对应 section 标 SKIP（不影响 verdict）

### 输出格式示例（你的脚本运行时应该看起来像这样）

```
╔══════════════════════════════════════════════════════════╗
║       MateClaw Browser Agent Phase 1 — Audit             ║
║       Run at: 2026-05-28T18:30:00Z                       ║
╚══════════════════════════════════════════════════════════╝

=== 1. Test Suite Status ===
  java-browser-tests   PASS (47 tests, 12.3s)
  ts-native-host       PASS (18 tests, 3.4s)
  ts-extension         PASS (14 tests, 2.8s)

=== 2. Build Sanity ===
  mateclaw-browser-bridge/dist/cmd/bridge.js       OK
  mateclaw-extension/dist/manifest.json            OK
  mateclaw-extension/dist/sidepanel.html           OK
  mateclaw-extension/dist/service-worker.js        OK

=== 3. Codex Invariants ===
  [1] untrusted-event ban (.click() / dispatchEvent)        PASS  (0 matches)
  [2] session_id ownership                                  PASS  (0 matches)
  [3] register CAS atomic compute                           PASS  (0 matches)
  [4] real auth APIs (no JwtService.parseUserId)            PASS  (0 matches)
  [5] single ws.on('message') reader                        PASS  (1 match in client.ts:N)
  [6] HeartbeatTimeoutError exported                        PASS  (1 match)
  [7] prefers-reduced-motion in visual code                 N/A   (no visual code in Phase 1)

=== 4. Summary ===
  Tests:        PASS  (3/3)
  Builds:       PASS  (4/4 artifacts)
  Invariants:   PASS  (6/6 evaluated, 1 N/A)
  Overall:      GREEN

verdict: GREEN
```

## 测试方法

跑两遍验证脚本鲁棒：

1. **冷跑**（Codex 沙盒里基本没有 mvn / pnpm / Java，所以会大量 WARN-SKIP）：
   ```bash
   bash scripts/audit-phase-1.sh || echo "exit=$?"
   ```
   预期：grep 部分（section 3）能跑（不需要外部工具）；其它 section 标 SKIP；exit 0 或 1（视 invariants 命中情况）

2. **构造负样例自测**：临时创建一个有 `.click()` 字面量的 TS 文件，确认 invariant #1 fail：
   ```bash
   mkdir -p mateclaw-extension/src
   echo "el.click();" > mateclaw-extension/src/__test_bad.ts
   bash scripts/audit-phase-1.sh
   # 应该看到 invariant #1 FAIL，打印命中行
   rm mateclaw-extension/src/__test_bad.ts
   ```

## 验收

- [ ] 单文件 `scripts/audit-phase-1.sh`，约 150-250 行
- [ ] `bash -n scripts/audit-phase-1.sh` 语法 OK
- [ ] `NO_COLOR=1` 时无 ANSI 残留
- [ ] `--quick` 跳过 mvn
- [ ] 工具缺失时不 abort，标 SKIP 继续
- [ ] 7 个 invariants 都用 grep 实现，不写规则文件
- [ ] 输出末尾必有 `verdict: GREEN|YELLOW|RED` 一行
- [ ] 负样例自测能让 invariant #1 fail

## 交付格式

```
========== FILE: scripts/audit-phase-1.sh ==========
<内容>
========== SELF-TEST OUTPUT 1 (clean run, tools likely missing) ==========
<bash scripts/audit-phase-1.sh 的输出>
========== SELF-TEST OUTPUT 2 (negative case: bad .click() injected) ==========
<负样例自测输出，证明能抓到>
========== COMMIT MSG ==========
chore(scripts): add scripts/audit-phase-1.sh for final Phase 1 audit

One-shot script: runs the three test suites, verifies build artifacts
exist, greps for the seven Codex-flagged invariants
(untrusted-event ban, session_id ownership, register CAS, real auth
APIs, single ws.on('message') reader, HeartbeatTimeoutError
exported, prefers-reduced-motion stub), prints a colored summary,
exits with GREEN/YELLOW/RED status.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

不要在 commit msg 之外添加额外总结文本。
