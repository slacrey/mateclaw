CREATE TABLE IF NOT EXISTS mate_lead_template (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    workspace_id        BIGINT       NOT NULL,
    platform            VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    keyword             VARCHAR(256),
    sort_mode           VARCHAR(64),
    video_limit         INT          NOT NULL DEFAULT 50,
    comment_match_rule  MEDIUMTEXT,
    dm_draft            VARCHAR(2048),
    engage              TINYINT      NOT NULL DEFAULT 1,
    send_dm             TINYINT      NOT NULL DEFAULT 0,
    created_by          BIGINT,
    deleted             INT          NOT NULL DEFAULT 0,
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_lead_template_workspace (workspace_id, platform, deleted, update_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Reusable lead acquisition launch templates.';
