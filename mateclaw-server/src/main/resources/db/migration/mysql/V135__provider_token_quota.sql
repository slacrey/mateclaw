-- V135: Provider token quota defaults.

CREATE TABLE IF NOT EXISTS mate_provider_token_quota (
    id            BIGINT       NOT NULL PRIMARY KEY,
    workspace_id  BIGINT       NOT NULL,
    provider_id   VARCHAR(64)  NOT NULL,
    limit_tokens  BIGINT       NOT NULL,
    used_tokens   BIGINT       NOT NULL DEFAULT 0,
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    UNIQUE KEY uk_provider_token_quota_workspace_provider (workspace_id, provider_id)
);

INSERT INTO mate_provider_token_quota (id, workspace_id, provider_id, limit_tokens, used_tokens, create_time, update_time)
SELECT CAST(CONV(SUBSTRING(SHA2(CONCAT(w.id, ':dashscope'), 256), 1, 15), 16, 10) AS UNSIGNED),
       w.id, 'dashscope', 2000000, 0, NOW(), NOW()
  FROM mate_workspace w
 WHERE NOT EXISTS (
       SELECT 1 FROM mate_provider_token_quota q
        WHERE q.workspace_id = w.id AND q.provider_id = 'dashscope'
   );

INSERT INTO mate_provider_token_quota (id, workspace_id, provider_id, limit_tokens, used_tokens, create_time, update_time)
SELECT CAST(CONV(SUBSTRING(SHA2(CONCAT(w.id, ':deepseek'), 256), 1, 15), 16, 10) AS UNSIGNED),
       w.id, 'deepseek', 3000000, 0, NOW(), NOW()
  FROM mate_workspace w
 WHERE NOT EXISTS (
       SELECT 1 FROM mate_provider_token_quota q
        WHERE q.workspace_id = w.id AND q.provider_id = 'deepseek'
   );
