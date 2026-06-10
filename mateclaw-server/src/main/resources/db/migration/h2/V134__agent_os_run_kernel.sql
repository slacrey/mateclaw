-- V134: Agent OS durable run kernel and browser/lead-acquisition facts.
-- H2 dialect. Large browser artifacts stay outside the main tables and are
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
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agent_run_workspace_status
    ON mate_agent_run (workspace_id, status, deleted);
CREATE INDEX IF NOT EXISTS idx_agent_run_conversation
    ON mate_agent_run (conversation_id, create_time);
CREATE INDEX IF NOT EXISTS idx_agent_run_trace
    ON mate_agent_run (trace_id);

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
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_step_run_status
    ON mate_agent_step (run_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_step_key
    ON mate_agent_step (run_id, step_key, attempt);
CREATE INDEX IF NOT EXISTS idx_agent_step_idempotency
    ON mate_agent_step (run_id, idempotency_key);

CREATE TABLE IF NOT EXISTS mate_agent_event (
    id              BIGINT       NOT NULL PRIMARY KEY,
    run_id          BIGINT       NOT NULL,
    step_id         BIGINT,
    event_type      VARCHAR(96)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL DEFAULT 'info',
    payload_json    CLOB,
    artifact_ids    VARCHAR(1024),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_event_run_created
    ON mate_agent_event (run_id, create_time);
CREATE INDEX IF NOT EXISTS idx_agent_event_type
    ON mate_agent_event (event_type, create_time);

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
    metadata_json   CLOB,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_artifact_uri
    ON mate_agent_artifact (artifact_uri);
CREATE INDEX IF NOT EXISTS idx_agent_artifact_run_step
    ON mate_agent_artifact (run_id, step_id);
CREATE INDEX IF NOT EXISTS idx_agent_artifact_workspace
    ON mate_agent_artifact (workspace_id, create_time);

CREATE TABLE IF NOT EXISTS mate_agent_pause (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    run_id              BIGINT       NOT NULL,
    step_id             BIGINT,
    pause_kind          VARCHAR(64)  NOT NULL,
    pause_token         VARCHAR(160) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    external_ref        VARCHAR(256),
    resume_deadline     TIMESTAMP,
    resume_payload_ref  VARCHAR(512),
    paused_at           TIMESTAMP    NOT NULL,
    resumed_at          TIMESTAMP,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_pause_token
    ON mate_agent_pause (pause_token);
CREATE INDEX IF NOT EXISTS idx_agent_pause_run_status
    ON mate_agent_pause (run_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_pause_deadline
    ON mate_agent_pause (status, resume_deadline);

CREATE TABLE IF NOT EXISTS mate_browser_session (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    workspace_id        BIGINT,
    run_id              BIGINT,
    edge_session_id     VARCHAR(128) NOT NULL,
    browser_kind        VARCHAR(32)  NOT NULL,
    subject             VARCHAR(128),
    status              VARCHAR(32)  NOT NULL,
    agent_version       VARCHAR(64),
    last_heartbeat_at   TIMESTAMP,
    metadata_json       CLOB,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_browser_session_edge
    ON mate_browser_session (edge_session_id, status);
CREATE INDEX IF NOT EXISTS idx_browser_session_run
    ON mate_browser_session (run_id, status);

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
    last_seen_at        TIMESTAMP,
    metadata_json       CLOB,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_browser_tab_session_role
    ON mate_browser_tab (browser_session_id, role, status);
CREATE INDEX IF NOT EXISTS idx_browser_tab_run
    ON mate_browser_tab (run_id, status);

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
    metadata_json       CLOB,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_browser_region_run_key
    ON mate_browser_region (run_id, region_key);
CREATE INDEX IF NOT EXISTS idx_browser_region_tab_key
    ON mate_browser_region (tab_id, region_key);

CREATE TABLE IF NOT EXISTS mate_lead_task (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    run_id              BIGINT       NOT NULL,
    workspace_id        BIGINT       NOT NULL,
    platform            VARCHAR(64)  NOT NULL,
    keyword             VARCHAR(256),
    sort_mode           VARCHAR(64),
    status              VARCHAR(32)  NOT NULL,
    input_json          CLOB,
    summary_json        CLOB,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lead_task_run
    ON mate_lead_task (run_id);
CREATE INDEX IF NOT EXISTS idx_lead_task_workspace
    ON mate_lead_task (workspace_id, platform, status);

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
    comment_text        CLOB,
    like_count          INT,
    reply_count         INT,
    matched             BOOLEAN      NOT NULL DEFAULT FALSE,
    match_score         DOUBLE,
    match_reason        VARCHAR(1024),
    metadata_json       CLOB,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lead_comment_task_video
    ON mate_lead_comment (task_id, video_key);
CREATE INDEX IF NOT EXISTS idx_lead_comment_match
    ON mate_lead_comment (task_id, matched, match_score);
CREATE INDEX IF NOT EXISTS idx_lead_comment_key
    ON mate_lead_comment (task_id, comment_key);

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
    raw_json            CLOB,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lead_profile_task
    ON mate_lead_profile (task_id);
CREATE INDEX IF NOT EXISTS idx_lead_profile_url
    ON mate_lead_profile (platform, profile_url);

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
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lead_engagement_task
    ON mate_lead_engagement (task_id, status);
CREATE INDEX IF NOT EXISTS idx_lead_engagement_profile
    ON mate_lead_engagement (profile_id, engagement_type);
