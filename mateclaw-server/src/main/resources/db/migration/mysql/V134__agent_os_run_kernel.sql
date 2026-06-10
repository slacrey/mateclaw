-- V134: Agent OS durable run kernel and browser/lead-acquisition facts.
-- MySQL dialect. Large browser artifacts stay outside the main tables and are
-- addressed by URI/hash metadata.

CREATE TABLE IF NOT EXISTS mate_agent_run (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    workspace_id        BIGINT       NOT NULL,
    conversation_id     BIGINT,
    trace_id            VARCHAR(128),
    run_type            VARCHAR(64)  NOT NULL,
    source_type         VARCHAR(64),
    source_ref          VARCHAR(256),
    status              VARCHAR(32)  NOT NULL,
    current_step_key    VARCHAR(128),
    checkpoint_ref      VARCHAR(512),
    input_ref           VARCHAR(512),
    output_ref          VARCHAR(512),
    failure_code        VARCHAR(128),
    failure_message     VARCHAR(2048),
    created_by          BIGINT,
    started_at          DATETIME(3),
    completed_at        DATETIME(3),
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted             INT          NOT NULL DEFAULT 0,
    KEY idx_agent_run_workspace_status (workspace_id, status, deleted),
    KEY idx_agent_run_conversation (conversation_id, create_time),
    KEY idx_agent_run_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Durable Agent OS run root.';

CREATE TABLE IF NOT EXISTS mate_agent_step (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    run_id              BIGINT       NOT NULL,
    parent_step_id      BIGINT,
    step_key            VARCHAR(128) NOT NULL,
    parent_step_key     VARCHAR(128),
    step_type           VARCHAR(64)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    attempt             INT          NOT NULL DEFAULT 1,
    idempotency_key     VARCHAR(256),
    policy_tags         VARCHAR(512),
    input_ref           VARCHAR(512),
    output_ref          VARCHAR(512),
    checkpoint_ref      VARCHAR(512),
    failure_code        VARCHAR(128),
    failure_message     VARCHAR(2048),
    duration_ms         BIGINT,
    token_input         INT,
    token_output        INT,
    started_at          DATETIME(3),
    completed_at        DATETIME(3),
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_agent_step_run_status (run_id, status),
    KEY idx_agent_step_key (run_id, step_key, attempt),
    KEY idx_agent_step_idempotency (run_id, idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Durable Agent OS step ledger.';

CREATE TABLE IF NOT EXISTS mate_agent_event (
    id              BIGINT       NOT NULL PRIMARY KEY,
    run_id          BIGINT       NOT NULL,
    step_id         BIGINT,
    event_type      VARCHAR(96)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL DEFAULT 'info',
    payload_json    MEDIUMTEXT,
    artifact_ids    VARCHAR(1024),
    create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_agent_event_run_created (run_id, create_time),
    KEY idx_agent_event_type (event_type, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Append-only Agent OS run event timeline.';

CREATE TABLE IF NOT EXISTS mate_agent_artifact (
    id              BIGINT       NOT NULL PRIMARY KEY,
    run_id          BIGINT,
    step_id         BIGINT,
    workspace_id    BIGINT       NOT NULL,
    artifact_uri    VARCHAR(512) NOT NULL,
    artifact_kind   VARCHAR(64)  NOT NULL,
    content_type    VARCHAR(128),
    storage_kind    VARCHAR(32)  NOT NULL,
    storage_ref     VARCHAR(1024),
    sha256          CHAR(64),
    size_bytes      BIGINT,
    metadata_json   MEDIUMTEXT,
    create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_artifact_uri (artifact_uri),
    KEY idx_agent_artifact_run_step (run_id, step_id),
    KEY idx_agent_artifact_workspace (workspace_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Artifact metadata for screenshots, DOM snapshots, and extracted data.';

CREATE TABLE IF NOT EXISTS mate_agent_pause (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    run_id              BIGINT       NOT NULL,
    step_id             BIGINT,
    pause_kind          VARCHAR(64)  NOT NULL,
    pause_token         VARCHAR(160) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    external_ref        VARCHAR(256),
    resume_deadline     DATETIME(3),
    resume_payload_ref  VARCHAR(512),
    paused_at           DATETIME(3)  NOT NULL,
    resumed_at          DATETIME(3),
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_agent_pause_token (pause_token),
    KEY idx_agent_pause_run_status (run_id, status),
    KEY idx_agent_pause_deadline (status, resume_deadline)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Durable pauses for approval, human takeover, and external callbacks.';

CREATE TABLE IF NOT EXISTS mate_browser_session (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    workspace_id        BIGINT,
    run_id              BIGINT,
    edge_session_id     VARCHAR(128) NOT NULL,
    browser_kind        VARCHAR(32)  NOT NULL,
    subject             VARCHAR(128),
    status              VARCHAR(32)  NOT NULL,
    agent_version       VARCHAR(64),
    last_heartbeat_at   DATETIME(3),
    metadata_json       MEDIUMTEXT,
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted             INT          NOT NULL DEFAULT 0,
    KEY idx_browser_session_edge (edge_session_id, status),
    KEY idx_browser_session_run (run_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Persistent browser runtime sessions.';

CREATE TABLE IF NOT EXISTS mate_browser_tab (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    browser_session_id  BIGINT       NOT NULL,
    run_id              BIGINT,
    tab_ref             VARCHAR(128) NOT NULL,
    role                VARCHAR(32)  NOT NULL,
    group_id            VARCHAR(128),
    url                 VARCHAR(2048),
    title               VARCHAR(512),
    status              VARCHAR(32)  NOT NULL,
    opened_by_step_id   BIGINT,
    last_seen_at        DATETIME(3),
    metadata_json       MEDIUMTEXT,
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_browser_tab_session_role (browser_session_id, role, status),
    KEY idx_browser_tab_run (run_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'MateClaw-owned browser tabs and roles.';

CREATE TABLE IF NOT EXISTS mate_browser_region (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    run_id              BIGINT,
    tab_id              BIGINT,
    region_key          VARCHAR(160) NOT NULL,
    label               VARCHAR(256),
    x                   DOUBLE,
    y                   DOUBLE,
    width               DOUBLE,
    height              DOUBLE,
    safe_x              DOUBLE,
    safe_y              DOUBLE,
    confidence          DOUBLE,
    source              VARCHAR(64),
    fingerprint         VARCHAR(256),
    metadata_json       MEDIUMTEXT,
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_browser_region_run_key (run_id, region_key),
    KEY idx_browser_region_tab_key (tab_id, region_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Detected browser regions such as comment panes.';

CREATE TABLE IF NOT EXISTS mate_lead_task (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    run_id              BIGINT       NOT NULL,
    workspace_id        BIGINT       NOT NULL,
    platform            VARCHAR(64)  NOT NULL,
    keyword             VARCHAR(256),
    sort_mode           VARCHAR(64),
    status              VARCHAR(32)  NOT NULL,
    input_json          MEDIUMTEXT,
    summary_json        MEDIUMTEXT,
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_lead_task_run (run_id),
    KEY idx_lead_task_workspace (workspace_id, platform, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Lead acquisition task facts.';

CREATE TABLE IF NOT EXISTS mate_lead_comment (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    task_id             BIGINT       NOT NULL,
    run_id              BIGINT       NOT NULL,
    video_key           VARCHAR(256),
    comment_key         VARCHAR(256),
    parent_comment_key  VARCHAR(256),
    author_name         VARCHAR(256),
    author_profile_url  VARCHAR(1024),
    author_avatar_url   VARCHAR(1024),
    comment_text        MEDIUMTEXT,
    like_count          INT,
    reply_count         INT,
    matched             TINYINT      NOT NULL DEFAULT 0,
    match_score         DOUBLE,
    match_reason        VARCHAR(1024),
    metadata_json       MEDIUMTEXT,
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_lead_comment_task_video (task_id, video_key),
    KEY idx_lead_comment_match (task_id, matched, match_score),
    KEY idx_lead_comment_key (task_id, comment_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Extracted comments bound to author/profile facts.';

CREATE TABLE IF NOT EXISTS mate_lead_profile (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    task_id             BIGINT       NOT NULL,
    run_id              BIGINT       NOT NULL,
    platform            VARCHAR(64)  NOT NULL,
    profile_url         VARCHAR(1024) NOT NULL,
    display_name        VARCHAR(256),
    handle              VARCHAR(256),
    avatar_url          VARCHAR(1024),
    bio                 VARCHAR(2048),
    raw_json            MEDIUMTEXT,
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_lead_profile_task (task_id),
    KEY idx_lead_profile_url (platform, profile_url(255))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Lead profile facts.';

CREATE TABLE IF NOT EXISTS mate_lead_engagement (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    task_id             BIGINT       NOT NULL,
    run_id              BIGINT       NOT NULL,
    profile_id          BIGINT,
    comment_id          BIGINT,
    engagement_type     VARCHAR(64)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    draft_text          VARCHAR(2048),
    failure_code        VARCHAR(128),
    failure_message     VARCHAR(2048),
    evidence_ref        VARCHAR(512),
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_lead_engagement_task (task_id, status),
    KEY idx_lead_engagement_profile (profile_id, engagement_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Follow/DM/draft engagement attempts and evidence.';
