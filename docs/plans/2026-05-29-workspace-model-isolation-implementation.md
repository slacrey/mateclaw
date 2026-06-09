# Workspace Model Isolation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make model providers, provider credentials, and model rows isolated per workspace.

**Architecture:** Add `workspace_id` to provider/model tables, pass the current workspace id from controllers into model services, and filter every provider/model operation by workspace. New workspaces are seeded from workspace `1` templates so registered teams start with an independent catalog.

**Tech Stack:** Spring Boot, MyBatis-Plus, Flyway-style SQL migrations for MySQL/H2, JUnit 5/Mockito.

---

### Task 1: Workspace-Scoped Service Tests

**Files:**
- Create/modify: `mateclaw-server/src/test/java/vip/mate/llm/service/ModelProviderServiceWorkspaceIsolationTest.java`
- Create/modify: `mateclaw-server/src/test/java/vip/mate/llm/service/ModelConfigServiceWorkspaceIsolationTest.java`

**Steps:**

1. Write failing tests that create mocked provider/model mapper data for two workspace ids.
2. Assert listing providers only sees the requested workspace.
3. Assert updating workspace `10` provider config does not update workspace `20`.
4. Assert default model resolution is workspace scoped.
5. Run:
   `mvn -pl mateclaw-server -Dtest=ModelProviderServiceWorkspaceIsolationTest,ModelConfigServiceWorkspaceIsolationTest test`
6. Expected before implementation: failures because service methods do not accept/filter workspace id.

### Task 2: Schema and Entity Changes

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/model/ModelProviderEntity.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/model/ModelConfigEntity.java`
- Modify: `mateclaw-server/src/main/resources/db/migration/mysql/V1__baseline_schema.sql`
- Modify: `mateclaw-server/src/main/resources/db/migration/h2/V1__baseline_schema.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/mysql/V131__workspace_model_isolation.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/h2/V131__workspace_model_isolation.sql`

**Steps:**

1. Add `id` and `workspaceId` fields to `ModelProviderEntity`.
2. Add `workspaceId` to `ModelConfigEntity`.
3. Update baseline schemas.
4. Add migrations that backfill/copy existing provider and model rows for every workspace.
5. Add unique/index constraints for workspace-scoped lookups.

### Task 3: Service API Refactor

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/service/ModelProviderService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/service/ModelConfigService.java`
- Modify: dependent tests under `mateclaw-server/src/test/java/vip/mate/llm/service/`

**Steps:**

1. Add `workspaceId` parameters to provider and model service methods.
2. Filter reads by `workspace_id`.
3. Scope writes by `(workspace_id, provider_id)` or model id plus workspace.
4. Ensure duplicates are checked within the workspace only.
5. Run provider/model service tests.

### Task 4: Controller Workspace Wiring

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/controller/ModelConfigController.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/controller/ProviderPoolController.java`
- Modify OAuth/embedding callers only where they mutate provider credentials or resolve models.

**Steps:**

1. Read `X-Workspace-Id` on Settings / Models endpoints with fallback `1`.
2. Pass workspace id to provider/model services.
3. Keep global admin-only endpoints global only where they are truly system-wide.
4. Update controller tests.

### Task 5: Workspace Seeding

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/workspace/core/service/WorkspaceService.java`
- Add service helper in `ModelProviderService` or a small seeding service.
- Update registration/workspace tests.

**Steps:**

1. Add `seedWorkspaceModels(workspaceId)` helper.
2. Copy provider catalog and built-in models from workspace `1`.
3. Clear remote credentials for newly created workspaces.
4. Call the helper from `WorkspaceService.create` after workspace insert.
5. Assert newly registered workspace has provider/model rows.

### Task 6: Verification

Run:

```bash
mvn -pl mateclaw-server -Dtest=ModelProviderServiceWorkspaceIsolationTest,ModelConfigServiceWorkspaceIsolationTest,ModelConfigControllerWorkspaceRoleTest,AuthServiceRegisterTest,WorkspaceControllerMembersAuthTest,ModelProviderServiceEnableTest,ModelProviderServiceCustomProviderTest test
```

Then inspect:

```bash
git diff --stat
git status --short --branch
```
