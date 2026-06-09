# Codex Task 05 — STRIDE Threat Model for Browser Agent

> 你（Codex）要为 MateClaw Browser Agent 写一份 **STRIDE 威胁模型文档**，
> 落在 `docs/security/browser-agent-threat-model.md`。这是一份独立的安全
> 评审材料，Phase 3 上线前 InfoSec 团队会读它。

## 项目背景

MateClaw Browser Agent 是个三进程系统，让 LLM-driven 的工作流在用户的真实
Chrome 浏览器里操作页面（比如自动登录某个站点 → 翻评论 → 标记线索）。三个
进程：

1. **Control Plane** — Java Spring Boot 单体服务器，跑在 `localhost:18088`（本机部署）或公司内网。暴露 WebSocket `/api/v1/browser/edge` 给 Native Host 长连。同时持有 LLM API key、用户的业务凭据等敏感配置。
2. **Native Host** — TypeScript/Node 单文件可执行（`bridge.exe`），跑在用户本机。通过 Chrome Native Messaging stdio 协议与扩展通信，通过 Bearer-over-WSS 连 Control Plane。**它是 `session_id` 的唯一拥有者**：任何 envelope 经它转发时它都会强制盖戳（拒绝信任扩展端给的 session_id —— 防止恶意 / 被攻陷的扩展冒充别人的 session）。
3. **Chrome Extension (MV3)** — TypeScript + Vue 3。Service Worker 维护
   `chrome.runtime.connectNative` 长连。Phase 1 只有手动 Ping 按钮；Phase 2
   会加 `chrome.debugger.attach` 拿 CDP，能控制鼠标键盘和读 DOM。

### 通讯协议（v1）

JSON 信封：

```json
{ "v": 1, "msg_id": "uuid", "kind": "ping|action.execute|...",
  "ts": 0, "trace_id": "uuid", "session_id": "sess-...",
  "in_reply_to": "...", "payload": { ... } }
```

- 握手时 Bearer auth（JWT 或 Personal Access Token）走 HTTP `Authorization`
  头，**handshake-time 失败回 HTTP 401**；upgrade 完成后再失败用 WS close
  4401（reserved，未在 Phase 1 实现）。
- 心跳 10s（hello.ack 可 override）；3× 不响应 → 4408 close + 客户端指数退避
  重连。
- v1 receivers 必须 silently log+drop 未知 kinds（forward compat）。
- v2+ 引入 `tab_ref` 字段告诉 Native Host 命令作用在哪个 tab；
  Native Host 解析 `"main"|"active"|<int>`。

### 资产清单（让你识别威胁面）

| 资产 | 位置 | 敏感度 |
|---|---|---|
| 用户业务 cookies / sessions | Chrome 用户 profile（一直在）| 极高 |
| LLM API keys / 业务 secrets | Control Plane `application.yml` + DB | 极高 |
| PAT（用户 → CP 鉴权） | Native Host 本地存储（`~/.mateclaw/bridge.yaml`）+ CP DB | 高 |
| 用户 DOM 内容 / 截图 | Phase 2+ 流过 WSS，可能落 LLM | 中（取决于站点） |
| Native Messaging manifest | `%LOCALAPPDATA%` 或 `~/Library/...` | 中（允许的 extension origin） |
| Chrome extension code | `mateclaw-extension/dist/` 落地用户机器 | 中 |
| Bridge 可执行 | `bridge.exe`，用户机器 | 高（特权 = CP 通讯 + chrome.debugger 入口） |

## 你要交付的文件

### `docs/security/browser-agent-threat-model.md`

一份 **600–900 行** 的 markdown 文档。结构必须包含以下章节，顺序如此：

#### 1. Scope

- 在 scope 的内容：Phase 1 v1.2（三进程，Bearer-over-WSS，Native Messaging stdio）和 Phase 2（CDP attach + 动作分发 + 视觉指示器 + tab groups）。
- 不在 scope：Phase 3 多租户 SaaS、mTLS、SOP synthesis 引擎、第三方平台 ToS。
- Assumption（明列 5–8 条）：用户本机不被 root；Chrome 是官方版而非 fork；用户能正确装 / 不装扩展；网络层 TLS 健全；JWT/PAT 不会被 server-side 误日志。

#### 2. Architecture Diagram

ASCII / 文字描述的三进程拓扑（参考"项目背景"重写一遍，注明每条边的协议、加密、鉴权方式）。

#### 3. Trust Boundaries

列出 6 条 trust boundary，每条用 1–2 段说明：
- Browser ↔ Native Host（NM stdio）
- Native Host ↔ Control Plane（WSS）
- Control Plane ↔ LLM provider（API key）
- Control Plane ↔ Database（JWT/PAT 存哈希）
- Chrome Extension ↔ Web page（content scripts isolated world）
- Phase 2: Extension ↔ chrome.debugger 协议（带"automation banner"）

每条 boundary 明确说"穿过这条边的所有信息必须假定对面不可信"。

#### 4. STRIDE Threats

**对每条 trust boundary，按 STRIDE 六类列威胁**：
- **S**poofing（冒充身份）
- **T**ampering（篡改数据）
- **R**epudiation（事后否认操作）
- **I**nformation disclosure（信息泄漏）
- **D**enial of service（拒绝服务）
- **E**levation of privilege（越权）

每个威胁写成结构化条目：

```markdown
##### T-NH-WSS-S1 — Spoofing: attacker connects to CP impersonating a legit user

- **Asset at risk**: user session in Control Plane → access to user's LLM agent
- **Vector**: stolen PAT plaintext; attacker dials `wss://cp.example/api/v1/browser/edge` with `Authorization: Bearer <stolen>`
- **Impact**: full agent access for the lifetime of the PAT
- **Likelihood**: medium (PAT travels in user config; backup files; clipboard history)
- **Mitigation (current)**: PATs hashed in DB; `findActiveByPlaintext` is the only lookup path; PATs can be revoked via UI
- **Mitigation (gap)**: no mTLS, no device binding; Phase 3 should bind PAT to a `device_id` + machine fingerprint
- **Detection**: CP can log `subject + IP + userAgent` per session establishment; anomaly = same PAT from 2 IPs in 5 min
```

至少 25 条威胁条目。**重点覆盖**这些已知的 high-impact 路径：

| ID | 必写 |
|---|---|
| Spoofing: stolen PAT | ✓ |
| Spoofing: extension uses wrong session_id (mitigated by NH stamping) | ✓ |
| Tampering: malicious extension drops untrusted MouseEvent (mitigated by CDP-only) | ✓ |
| Tampering: MITM on WSS (mitigated by TLS) | ✓ |
| Repudiation: who-clicked-what audit log gap | ✓ |
| Info Disclosure: LLM trained on user DOM content (call out provider data handling) | ✓ |
| Info Disclosure: screenshot pipeline leaks PII to LLM in Phase 2 | ✓ |
| DoS: SW kill loop crashes browser | ✓ |
| DoS: bridge reconnect spam against CP | ✓ |
| EoP: extension `<all_urls>` + `debugger` permission abuse if extension is compromised | ✓ |
| EoP: malicious Native Host manifest installed by other software pointing to fake bridge | ✓ |
| Repudiation: user denies issuing an automated action → no signed receipt | ✓ |
| ... |

每条都用上述结构化格式。

#### 5. Threat Matrix Summary

一张大表：纵轴 25+ threats，横轴 `Likelihood (L/M/H)` × `Impact (L/M/H)` × `Phase (1/2/3)` × `Status (mitigated/partial/open)`。

#### 6. Mitigations & Roadmap

按 Phase 列已落地 / Phase 2 计划做 / Phase 3 计划做的 mitigation：

- **Phase 1 (now)**：session_id 强制盖戳（防 spoofing）；Bearer 在 handshake；PAT 哈希；WS close 码分类；forward-compat unknown-kind drop（防 protocol smuggle）。
- **Phase 2**：CDP-only input dispatch（防 untrusted-event tampering）；visual indicator banner（让用户随时能 stop）；tab-ref strict resolution（防 cross-tab spoof）；hide-for-tool-use（防截图泄漏指示器导致 LLM confusion）。
- **Phase 3**：mTLS；PAT device binding；signed action receipts；audit log to immutable store；post-upgrade revocation check + 4401 close 实现。

#### 7. Residual Risks Acceptance

一节 1–2 页的"我们 Phase 2 后还接受这些风险，原因是 …"，明列：
- 同一用户被 OS-level root 攻陷后，bridge / extension / CP 都失守 → 接受
- 用户被钓鱼装错误的 extension → 接受（依赖 Chrome Web Store 审核）
- LLM provider 自己的数据保留政策 → 接受（外部因素）

#### 8. References

- Chrome MV3 extension security model 官方页（链接）
- Chrome Native Messaging 官方文档（链接）
- OWASP Top 10
- MITRE ATT&CK for Browser Extensions (T1176)
- Anthropic / OpenAI prompt injection 公开文档

只列**真实可访问**的 URL —— 不要造。如果某个引用你不确定，标 TODO 让维护
者补。

## 验收

- [ ] 文件路径 `docs/security/browser-agent-threat-model.md`
- [ ] 8 个章节齐全
- [ ] ≥ 25 条结构化威胁条目，每条覆盖 6 个字段（Asset/Vector/Impact/Likelihood/Mitigation current/Mitigation gap/Detection）
- [ ] Threat matrix 表完整
- [ ] Phase roadmap 表清楚（什么在 Phase 1/2/3）
- [ ] Residual risks 章节明确写"我们接受"
- [ ] References 全部是真实可访问的 URL，造的标 TODO
- [ ] **不要引用本文档之外的任何 MateClaw 文件**（让 InfoSec 阅读时不需要再
  打开别的文件）。所有协议细节直接 inline 写

## 交付格式

```
========== FILE: docs/security/browser-agent-threat-model.md ==========
<内容>
========== COMMIT MSG ==========
docs(security): STRIDE threat model for Browser Agent Phase 1+2

Self-contained security review document for the three-process browser
agent system (Control Plane / Native Host / Chrome Extension). Covers
6 trust boundaries, 25+ structured threats (one per STRIDE category
per boundary, with Asset/Vector/Impact/Likelihood/Mitigation/Detection),
a threat matrix, and a phase-keyed mitigation roadmap. Phase 1 ships
PAT hashing + session_id ownership + Bearer-over-WSS + forward-compat
unknown-kind drop; Phase 2 adds CDP-only input + visual stop banner +
tab_ref strict resolution; Phase 3 plans mTLS + device binding + signed
action receipts + post-upgrade revocation.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```
