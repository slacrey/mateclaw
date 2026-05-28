# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Repository shape

Maven monorepo + two pnpm packages. The Maven reactor (root [pom.xml](pom.xml)) only contains the Java modules; the Vue projects are built separately and their output is folded back into the server JAR.

| Path | Role |
|---|---|
| `mateclaw-server/` | Spring Boot 3.5 application (the JAR you ship). All controllers, agent runtime, channels, persistence, Flyway migrations |
| `mateclaw-plugin-api/` | Java SPI consumed by both the server and external plugins ([MateClawPlugin](mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/MateClawPlugin.java)). Keep stable — third parties depend on it |
| `mateclaw-plugin-sample/` | Reference plugin implementation, also a smoke test for the SPI |
| `mateclaw-ui/` | Vue 3 + Vite admin SPA. `pnpm build` writes directly into `mateclaw-server/src/main/resources/static/` (see [vite.config.ts](mateclaw-ui/vite.config.ts)) — there is no separate frontend artifact |
| `mateclaw-webchat/` | Embeddable widget. `pnpm build` copies its dist into `mateclaw-server/.../static/webchat/` |

All four Java versions are pinned through `${revision}` (currently `1.4.0`) in the root POM. `flatten-maven-plugin` substitutes this into installed POMs — never hard-code a version in a child module.

## Common commands

### Backend (from `mateclaw-server/`)
- `mvn spring-boot:run` — dev profile (H2 file at `data/mateclaw.mv.db`), port 18088. Zero env vars to start; LLM providers are added later in the admin UI.
- `mvn clean package` — produces the fat JAR via `spring-boot-maven-plugin:repackage`. `-DskipTests` to skip the suite.
- `mvn test` — runs the full suite. Mockito on JDK 21 needs the byte-buddy agent statically attached — surefire already wires this through `${net.bytebuddy:byte-buddy-agent:jar}`; do not strip the `<argLine>`.
- `mvn test -P media-gen` — runs only JUnit 5 tests tagged `@Tag("media-gen")` (image / video providers). Use for iterating without paying the ~50 min full-suite cost.
- `mvn -P aliyun-first ...` — pushes Aliyun mirrors to the front of the repository fallback chain. Useful from mainland China; safe to ignore elsewhere.
- Single test class: `mvn test -Dtest=ClassName` (or `ClassName#methodName`). Surefire is configured at the root POM, so it works from any module.

### Frontend (from `mateclaw-ui/`)
- `pnpm install && pnpm dev` — dev server on 5173, proxies `/api` (with `ws:true` for the Talk Mode WebSocket) and `/skill-assets` to the backend on 18088.
- `pnpm build` — runs `check-snowflake-precision.sh` (Bash; the script lives at `../scripts/` per the script path, *currently missing from the tree* — keep an eye on this if a fresh CI fails the build), then `vue-tsc --noEmit`, then `vite build`. Heap is bumped to 6 GB on purpose; do not lower it. The build writes into the server's `static` resource directory, so a backend rebuild picks up the new SPA automatically.
- `ANALYZE=1 pnpm build` — also emits `dist/stats.html` from rollup-plugin-visualizer. Opt-in.
- `pnpm lint` — ESLint + the snowflake precision check.

### Docker
```bash
cp .env.example .env   # set DB_PASSWORD, DB_ROOT_PASSWORD, JWT_SECRET, MATECLAW_CORS_ALLOWED_ORIGINS
docker compose up -d   # http://localhost:18080 → container's 18088
```
The Dockerfile is multi-stage: Node builds the SPA into `/static`, Maven builds the JAR with that `/static` copied into the classpath, and the final stage runs on `mcr.microsoft.com/playwright:v<pinned>-noble` (bundled Chromium + glibc) with `openjdk-21-jre-headless`, CJK fonts, poppler-utils, and tesseract layered in.

### Default credentials
`admin / admin123`.

## Architecture you have to know up front

### The agent runtime is a StateGraph, not a chat loop
The engine is [spring-ai-alibaba-graph-core](https://github.com/alibaba/spring-ai-alibaba)'s `StateGraph`. Two graphs are compiled per agent:
- **ReAct** — [StateGraphReActAgent](mateclaw-server/src/main/java/vip/mate/agent/graph/StateGraphReActAgent.java). Nodes: `Reasoning → Action → Observation → (loop) → Summarizing → FinalAnswer` plus `GoalEvaluation` and `LimitExceeded`. State flows through [MateClawStateKeys](mateclaw-server/src/main/java/vip/mate/agent/graph/state/MateClawStateKeys.java).
- **Plan-and-Execute** — [StateGraphPlanExecuteAgent](mateclaw-server/src/main/java/vip/mate/agent/graph/plan/StateGraphPlanExecuteAgent.java), under `agent/graph/plan/`.

If you're changing how an agent reasons, the entry points are `node/`, `edge/`, and `executor/` under `vip/mate/agent/graph/`. The `runtime` package powers the admin "Runtime Console" (`Settings → System → Runtime`) — `AgentRuntimeAggregator` is what every per-employee status pill reads.

### Tool plumbing — three independent registries, one binding
- **Built-in tools** under `vip/mate/tool/builtin/` (DateTime, Browser, Document extract/render, Image/Video/Music gen, Delegate-agent, Cron, …).
- **Skills** under `vip/mate/skill/` — `SKILL.md` packages with manifest + prompt + tool list + `LESSONS.md`. The installer pulls from local zip, git, or Skill Hub (`clawhub.ai` by default). The ACP bridge ([AcpSkillBridge](mateclaw-server/src/main/java/vip/mate/skill/acp/AcpSkillBridge.java)) lets Codex / Codex agents enter as skill cards.
- **MCP** under `vip/mate/tool/mcp/`. Spring AI's MCP client auto-configuration is **disabled** ([MateClawApplication](mateclaw-server/src/main/java/vip/mate/MateClawApplication.java)) — lifecycle is owned by `McpClientManager`. Per-agent binding is enforced in `vip/mate/agent/binding/`: installing an MCP tool for one digital employee never leaks to another.
- **Tool Guard** (`vip/mate/tool/guard/`) — RBAC + approval flow + path protection. Sensitive calls pause through `vip/mate/approval/` and the user resolves them from the channel-rendered card.

`mate.agent.tool.tool-result` controls a spill-to-disk pipeline: large tool outputs are written to a file and replaced inline with a preview pointing the agent at `read_file <path>`. `excluded-tools` (read_file, read_workspace_memory_file) must never be spilled — they would recurse the agent into reading the spill of the spill.

### LLM layer is provider-agnostic with health-tracked failover
Provider credentials are **not** in YAML or env vars — they live in `mate_model_provider` rows and are managed via `Settings → Models`. The fallbacks in [application.yml](mateclaw-server/src/main/resources/application.yml) (`spring.ai.dashscope.api-key: ${DASHSCOPE_API_KEY:configure-in-admin-ui}`) exist only so Spring AI Alibaba's auto-config doesn't fail context startup when nothing is set.

[vip/mate/llm/failover/](mateclaw-server/src/main/java/vip/mate/llm/failover/) walks a priority chain, parks dead providers in a cooldown window after `mate.llm.failover.health.failure-threshold` consecutive failures, and re-probes them. Anthropic / Codex / ChatGPT / Gemini OAuth integrations live next to their providers under `llm/anthropic/oauth`, `llm/chatgpt`, `llm/gemini`. Anthropic prompt-cache breakpoints are computed in `vip/mate/llm/cache/`.

### Persistence
- H2 (dev, default profile) at `./data/mateclaw.mv.db`. MySQL (prod profile `mysql`) via `mvn spring-boot:run -Dspring-boot.run.profiles=mysql` or `-Dspring.profiles.active=mysql`.
- Flyway owns the schema. Migrations live in `db/migration/h2/` and `db/migration/mysql/` and **must be kept in sync** — every new `Vn__*.sql` needs a matching file in both. `FlywayRepairConfig` calls `flyway.repair()` on every boot to absorb checksum drift from past releases; don't disable it.
- Flyway `placeholder-replacement` is **disabled globally** (see [application.yml](mateclaw-server/src/main/resources/application.yml)). Some migrations (e.g. the V85 ckjia MCP seed) intentionally store `${ENV_VAR}` literals so `McpClientManager.parseHeaders` can expand them at request time.
- MyBatis Plus pagination plugin is registered in `MateClawApplication`; **never** hard-code `DbType` on the inner interceptor — RFC-042 P0 was caused by hardcoding H2, which silently returned `total=0` to MySQL deployments.

### Workflow + triggers + wiki transformations (1.3.0+)
- `vip/mate/workflow/` — JSON DSL compiled by `WorkflowCompiler` (Pebble expressions, restricted subset; arbitrary template includes are blocked at the evaluator). Seven step modes: `sequential` / `fan_out` / `collect` / `conditional` / `await_approval` / `dispatch_channel` / `write_memory`. Natural-language drafting goes through `workflow/draftgen/`.
- `vip/mate/trigger/` — six pattern types (`cron` / `webhook` / `channel_message` / `agent_lifecycle` / `content_match` / `workflow_completion`). The `ingest/` package implements dedup, per-trigger rate limit, bot-self filter, recursion guard, fail-closed on unknown patterns. Don't loosen these defaults.
- `vip/mate/wiki/` — LLM Wiki digestion + hot cache + retrieval. The `job/strategy/` and `job/template/` subpackages are the Transformations engine (cross-material map-reduce, reverse-citation, per-template model picker).

### Channels and approval are siblings
Eight IM adapters under `vip/mate/channel/` (`dingtalk`, `feishu`, `discord`, `telegram`, `qq`, `slack`, `web`, plus WeChat / WeChat Work). All extend `AbstractChannelAdapter` and feed into the same `ChannelMessageRouter`. `ChannelHealthMonitor` parks failing channels so one outage doesn't take down the rest. Sensitive tool calls produce approval cards in the channel that originated the conversation — that flow lives in `vip/mate/channel/{feishu,...}/cards/tool_guard/` and `vip/mate/approval/`.

### Cron, hooks, plugins, auth
- `vip/mate/cron/` — Spring `@Scheduled` jobs, multi-instance safe via ShedLock (JDBC mode reuses the existing DataSource — no Redis). `CronJobRunner` **must not be `@Transactional`** ([architecture invariant](mateclaw-server/src/test/java/vip/mate/architecture)) — self-invocation would silently disable the proxy.
- `vip/mate/hook/` — declarative `mate_hook` rows, rate-limited per-event and globally (`mateclaw.hooks.global-rate-limit`).
- `vip/mate/plugin/` — loads `MateClawPlugin` JARs from `~/.mateclaw/plugins`. The SPI is in [mateclaw-plugin-api](mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/).
- `vip/mate/auth/` — Spring Security + JWT + Personal Access Tokens (`auth/pat/`). Default JWT secret triggers a startup WARN; production must set `JWT_SECRET`.

## Conventions and traps

- **Architecture invariants are tested** — `mateclaw-server/src/test/java/vip/mate/architecture/` has ArchUnit rules. Notably, every `ToolCallback` implementation must override `call(String, ToolContext)` so decorators like `LocaleAwareToolCallback` can forward `ChatOrigin` instead of silently dropping it. If you add a new tool callback and the arch test fails, do not relax the rule — implement the overload.
- **Tools disclosure mode** is `progressive` by default (`mateclaw.tools.disclosure.mode`). Heavy / extension-tier tools are hidden behind the `extension-tools` catalog until the model calls `enable_tool`. Don't switch to `legacy` to "make tools visible" — that bloats the prompt for every agent.
- **POI version is pinned** (5.5.1) at the root POM and **must align** with the POI version `tika-parser-microsoft-module` pulls transitively. A skew between `poi-ooxml-lite` and `poi-ooxml-full` silently breaks XSSF parsing.
- **Playwright runtime** version in [Dockerfile](mateclaw-server/Dockerfile) must match the `playwright` Maven coordinate. Microsoft rebuilds the image tag against the matching driver; a mismatch causes the Java driver to re-download browsers at startup and defeats the whole point of using the prebuilt image.
- **Tika pinning** — we depend on `tika-core` + `tika-parser-pdf-module` + `tika-parser-microsoft-module` only. Do not add `tika-parsers-standard-package` (~80 MB of formats the project never uses).
- **DashScope auto-config exclusion** — `DashScopeAgentAutoConfiguration` is excluded in `MateClawApplication`. It strictly requires `spring.ai.dashscope.api-key` at startup, which would break every Docker deployment that defers key entry to the UI. Don't re-enable it.
- **HikariCP sizing** — pool is set to 30 (issue #50). Cron concurrency (`CronJobService.MAX_CONCURRENT_CRON_RUNS=8`) plus `ChannelHealthMonitor`'s per-minute scan plus SSE plus channel adapters can saturate the default 10. If you raise concurrency, raise the pool.
- **Snowflake IDs** — the UI build runs `check-snowflake-precision.sh` because JavaScript `number` loses precision on 64-bit IDs. New API responses returning IDs should serialize as strings.

## Where to look when…

| You want to… | Start at |
|---|---|
| Add a built-in tool | `vip/mate/tool/builtin/` — implement `ToolCallback` and register through the existing factory pattern. Don't forget the `call(String, ToolContext)` override (arch test will fail otherwise). |
| Add a new MCP server seed | new Flyway migration in **both** `db/migration/h2/` and `db/migration/mysql/` |
| Add a new agent reasoning node | `vip/mate/agent/graph/node/` (+ wire it in the corresponding `*GraphBuilder`) |
| Add an LLM provider | `vip/mate/llm/` per-provider package + seed row in a migration; failover chain picks it up automatically |
| Add a workflow step kind | `vip/mate/workflow/compiler/` + runtime in `workflow/runtime/`; update Pebble subset evaluator if new expressions are needed |
| Add a channel | extend `AbstractChannelAdapter` under `vip/mate/channel/<name>/`; register with `ChannelManager` |
| Wire a new approval-gated tool | add the tool, then a guard rule (`mate_tool_guard_rule` seed + admin UI), then optionally a channel card under `channel/<name>/cards/tool_guard/` |

## Upgrade and operations notes

- [UPGRADING.md](UPGRADING.md) is the canonical reference for moving between versions. Flyway repair auto-heals known checksum drift; Ollama auto-discovery rewrites broken `:latest` defaults.
- Logs: `mateclaw-server/logs/mateclaw.log` + `mateclaw-error.log`. Flyway decisions are at INFO in the main log.
- Doctor tab (`Settings → Doctor`) and the admin Runtime Console (`Settings → System → Runtime`) are first-stop debugging surfaces — check them before adding new diagnostic endpoints.
