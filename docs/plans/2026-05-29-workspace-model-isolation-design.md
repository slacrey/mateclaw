# Workspace Model Isolation Design

Date: 2026-05-29
Status: Approved design

## Context

Model provider credentials and model rows currently live in global tables:

- `mate_model_provider`
- `mate_model_config`

Neither table has `workspace_id`, so a new team's model settings affect every other team.

## Decision

Bind both provider configuration and model configuration to `workspace_id`.

Existing configuration should be copied to every existing workspace during migration so upgrades do not leave workspaces without models. New workspaces should receive their own copy of the provider catalog and built-in model rows.

## Data Model

Add `workspace_id` to:

- `mate_model_provider`
- `mate_model_config`

Use `workspace_id = 1` as the fallback/default when an API does not supply `X-Workspace-Id`, matching existing workspace behavior.

Because MyBatis-Plus does not handle composite primary keys well, introduce a surrogate `id` primary key on `mate_model_provider` and enforce business uniqueness with:

- `UNIQUE (workspace_id, provider_id)`

For models, add indexes and uniqueness around:

- `workspace_id`
- `(workspace_id, provider, model_name, deleted)`

## Migration

For MySQL and H2:

1. Add `workspace_id` to both model tables.
2. Add surrogate `id` to `mate_model_provider`.
3. Duplicate the existing provider rows for each workspace that does not already have them.
4. Duplicate the existing model rows for each workspace that does not already have them.
5. Add indexes/unique constraints.

Fresh baseline schema files should also include the final shape.

## Runtime Behavior

Controllers that serve Settings / Models read `X-Workspace-Id` and pass the resolved id into model services.

Service methods filter every provider/model read and write by workspace:

- list provider catalog
- list enabled providers
- enable/disable provider
- update provider config
- custom provider create/delete
- add/remove provider model
- discover/apply models
- active/default model resolution
- embedding model listing/default selection where user-visible choices are workspace scoped

Chat/runtime model resolution must use the active request workspace when available. Existing endpoints already receive `X-Workspace-Id`, so the controller/service boundary should carry that id explicitly instead of relying on global defaults.

## New Workspace Initialization

When `WorkspaceService.create` creates a workspace, seed model providers and built-in models from workspace `1` as templates:

- copy provider catalog rows with credentials cleared unless they represent local/no-key defaults
- copy built-in model rows
- keep enabled/catalog state so the new workspace has the same visible catalog shape

This gives every newly registered account its own team-owned model configuration.

## Testing

Backend tests should prove:

- provider list only returns rows for the requested workspace
- updating a provider config in workspace A does not affect workspace B
- models are listed and defaults resolved per workspace
- enabling a provider in workspace A does not enable it in workspace B
- new workspace creation seeds independent provider/model rows
- registration-created workspace receives seeded model configuration

Existing provider service tests should be updated to include workspace ids.
