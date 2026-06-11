CREATE TABLE IF NOT EXISTS mate_lead_template (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    workspace_id        BIGINT       NOT NULL,
    platform            VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    keyword             VARCHAR(256),
    sort_mode           VARCHAR(64),
    video_limit         INT          NOT NULL DEFAULT 50,
    comment_match_rule  CLOB,
    dm_draft            VARCHAR(2048),
    engage              BOOLEAN      NOT NULL DEFAULT TRUE,
    send_dm             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by          BIGINT,
    deleted             INT          NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lead_template_workspace
    ON mate_lead_template (workspace_id, platform, deleted, update_time);
