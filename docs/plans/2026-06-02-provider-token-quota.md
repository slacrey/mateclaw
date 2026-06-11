# Provider Token Quota Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add workspace-level token quotas for the built-in DashScope and DeepSeek providers, show remaining tokens in model cards, and reject chat requests after quota is exhausted.

**Architecture:** Persist quota rows by `(workspace_id, provider_id)` and initialize them when workspace model providers are seeded. `ModelProviderService` enriches provider DTOs for the model cards. Runtime enforcement uses the resolved provider id before LLM calls and records usage after provider usage metadata is known.

**Tech Stack:** Spring Boot, MyBatis Plus, Flyway H2/MySQL migrations, Vue 3, TypeScript, Element Plus.

---

### Task 1: Backend Quota Model And Service

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/llm/model/ProviderTokenQuotaEntity.java`
- Create: `mateclaw-server/src/main/java/vip/mate/llm/model/ProviderTokenQuotaDTO.java`
- Create: `mateclaw-server/src/main/java/vip/mate/llm/repository/ProviderTokenQuotaMapper.java`
- Create: `mateclaw-server/src/main/java/vip/mate/llm/service/ProviderTokenQuotaService.java`
- Create migrations in both `h2` and `mysql`
- Test: `mateclaw-server/src/test/java/vip/mate/llm/service/ProviderTokenQuotaServiceTest.java`

**Steps:** Write failing tests for default quota creation, remaining calculation, exhausted guard, and usage recording. Add entity, mapper, service, and migrations. Run targeted tests.

### Task 2: Provider DTO Enrichment

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/model/ProviderInfoDTO.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/service/ModelProviderService.java`
- Test: `mateclaw-server/src/test/java/vip/mate/llm/service/ModelProviderServiceWorkspaceIsolationTest.java`

**Steps:** Write failing test that `dashscope` and `deepseek` provider DTOs include quota fields. Enrich DTOs through `ProviderTokenQuotaService`. Verify existing provider tests.

### Task 3: Runtime Enforcement And Recording

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/chatmodel/ProviderChatModelFactory.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/agent/graph/NodeStreamingChatHelper.java`
- Test targeted unit tests where practical.

**Steps:** Check quota before building a provider chat model. Record prompt+completion tokens after stream completion for the resolved provider. Throw the exact user-facing quota message when exhausted.

### Task 4: Model Card UI

**Files:**
- Modify: `mateclaw-ui/src/types/index.ts`
- Modify: `mateclaw-ui/src/views/Settings/Models/ProviderCard.vue`
- Modify locale keys if needed.

**Steps:** Add quota fields to provider type. Show remaining tokens and a recharge button for quota-managed providers. Clicking shows `public/business-qr.svg` in a modal/popover.

### Task 5: Verification

Run backend targeted tests and frontend type/build or relevant test command. Use browser screenshot for the model card UI if a dev server is available.
