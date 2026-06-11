-- V133: Bind model providers and models to workspaces.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_model_provider'
             AND COLUMN_NAME = 'id');
SET @s := IF(@c = 0,
             'ALTER TABLE mate_model_provider ADD COLUMN id BIGINT NULL FIRST',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_model_provider'
             AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0,
             'ALTER TABLE mate_model_provider ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 AFTER id',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE mate_model_provider
   SET id = CAST(CONV(SUBSTRING(SHA2(CONCAT(provider_id, ':1'), 256), 1, 15), 16, 10) AS UNSIGNED)
 WHERE id IS NULL;

SET @pk := (SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'mate_model_provider'
              AND CONSTRAINT_TYPE = 'PRIMARY KEY'
            LIMIT 1);
SET @s := IF(@pk IS NOT NULL,
             'ALTER TABLE mate_model_provider DROP PRIMARY KEY',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_model_provider'
             AND INDEX_NAME = 'PRIMARY');
SET @s := IF(@i = 0,
             'ALTER TABLE mate_model_provider ADD PRIMARY KEY (id)',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_model_config'
             AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0,
             'ALTER TABLE mate_model_config ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 AFTER name',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO mate_model_provider (
    id, workspace_id, provider_id, name, api_key_prefix, chat_model, api_key, base_url,
    generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check,
    freeze_url, require_api_key, auth_type, oauth_access_token, oauth_refresh_token,
    oauth_expires_at, oauth_account_id, fallback_priority, enabled, create_time, update_time
)
SELECT
    CAST(CONV(SUBSTRING(SHA2(CONCAT(p.provider_id, ':', w.id), 256), 1, 15), 16, 10) AS UNSIGNED),
    w.id, p.provider_id, p.name, p.api_key_prefix, p.chat_model,
    CASE WHEN w.id = 1 THEN p.api_key ELSE '' END,
    p.base_url, p.generate_kwargs, p.is_custom, p.is_local,
    p.support_model_discovery, p.support_connection_check, p.freeze_url,
    p.require_api_key, p.auth_type,
    CASE WHEN w.id = 1 THEN p.oauth_access_token ELSE NULL END,
    CASE WHEN w.id = 1 THEN p.oauth_refresh_token ELSE NULL END,
    CASE WHEN w.id = 1 THEN p.oauth_expires_at ELSE NULL END,
    CASE WHEN w.id = 1 THEN p.oauth_account_id ELSE NULL END,
    p.fallback_priority, p.enabled, NOW(), NOW()
  FROM mate_workspace w
  JOIN mate_model_provider p ON p.workspace_id = 1
 WHERE w.id <> 1
   AND NOT EXISTS (
       SELECT 1 FROM mate_model_provider existing
        WHERE existing.workspace_id = w.id
          AND existing.provider_id = p.provider_id
   );

INSERT INTO mate_model_config (
    id, workspace_id, name, provider, model_name, description, temperature, max_tokens,
    top_p, builtin, enabled, is_default, max_input_tokens, enable_search, search_strategy,
    model_type, modalities, request_timeout_seconds, create_time, update_time, deleted
)
SELECT
    CAST(CONV(SUBSTRING(SHA2(CONCAT(m.id, ':', w.id), 256), 1, 15), 16, 10) AS UNSIGNED),
    w.id, m.name, m.provider, m.model_name, m.description, m.temperature, m.max_tokens,
    m.top_p, m.builtin, m.enabled, m.is_default, m.max_input_tokens, m.enable_search,
    m.search_strategy, m.model_type, m.modalities, m.request_timeout_seconds,
    NOW(), NOW(), m.deleted
  FROM mate_workspace w
  JOIN mate_model_config m ON m.workspace_id = 1
 WHERE w.id <> 1
   AND NOT EXISTS (
       SELECT 1 FROM mate_model_config existing
        WHERE existing.workspace_id = w.id
          AND existing.provider = m.provider
          AND existing.model_name = m.model_name
          AND existing.deleted = m.deleted
   );

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_model_provider'
             AND INDEX_NAME = 'uk_model_provider_workspace_provider');
SET @s := IF(@i = 0,
             'CREATE UNIQUE INDEX uk_model_provider_workspace_provider ON mate_model_provider(workspace_id, provider_id)',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_model_config'
             AND INDEX_NAME = 'idx_model_config_workspace_provider');
SET @s := IF(@i = 0,
             'CREATE INDEX idx_model_config_workspace_provider ON mate_model_config(workspace_id, provider, model_name)',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_model_config'
             AND INDEX_NAME = 'idx_model_config_workspace_default');
SET @s := IF(@i = 0,
             'CREATE INDEX idx_model_config_workspace_default ON mate_model_config(workspace_id, is_default, enabled)',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
