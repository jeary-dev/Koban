-- Koban schema v1 (SQLite). Timestamps are epoch milliseconds; UUIDs are text.

CREATE TABLE players (
    uuid       CHAR(36)    NOT NULL PRIMARY KEY,
    name       VARCHAR(32) NOT NULL,
    name_lower VARCHAR(32) NOT NULL,
    last_seen  BIGINT      NOT NULL
);
CREATE INDEX idx_players_name ON players (name_lower);

CREATE TABLE reports (
    id                 INTEGER     PRIMARY KEY AUTOINCREMENT,
    status             VARCHAR(16) NOT NULL,
    category           VARCHAR(64) NOT NULL,
    reported_uuid      CHAR(36)    NOT NULL,
    reported_name      VARCHAR(32) NOT NULL,
    server             VARCHAR(64) NOT NULL,
    world              VARCHAR(128),
    x                  INT,
    y                  INT,
    z                  INT,
    reporter_count     INT         NOT NULL DEFAULT 1,
    created_at         BIGINT      NOT NULL,
    last_reported_at   BIGINT      NOT NULL,
    updated_at         BIGINT      NOT NULL,
    claimed_by_uuid    CHAR(36),
    claimed_by_discord BIGINT,
    claimed_by_name    VARCHAR(64),
    claimed_at         BIGINT,
    outcome            VARCHAR(16),
    closed_by_uuid     CHAR(36),
    closed_by_discord  BIGINT,
    closed_by_name     VARCHAR(64),
    closed_at          BIGINT,
    discord_thread_id  BIGINT,
    discord_message_id BIGINT,
    discord_dirty      INT         NOT NULL DEFAULT 1,
    version            INT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_reports_status ON reports (status, last_reported_at);
CREATE INDEX idx_reports_reported ON reports (reported_uuid, created_at);
CREATE INDEX idx_reports_dirty ON reports (discord_dirty);

CREATE TABLE report_reporters (
    report_id     BIGINT       NOT NULL REFERENCES reports (id) ON DELETE CASCADE,
    reporter_uuid CHAR(36)     NOT NULL,
    reporter_name VARCHAR(32)  NOT NULL,
    category      VARCHAR(64)  NOT NULL,
    details       VARCHAR(512),
    reported_at   BIGINT       NOT NULL,
    feedback      VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    PRIMARY KEY (report_id, reporter_uuid)
);
CREATE INDEX idx_reporters_reporter ON report_reporters (reporter_uuid, feedback);

CREATE TABLE evidence (
    id            INTEGER  PRIMARY KEY AUTOINCREMENT,
    report_id     BIGINT   NOT NULL REFERENCES reports (id) ON DELETE CASCADE,
    reporter_uuid CHAR(36) NOT NULL,
    captured_at   BIGINT   NOT NULL,
    format        INT      NOT NULL,
    payload       TEXT     NOT NULL
);
CREATE INDEX idx_evidence_report ON evidence (report_id);

CREATE TABLE report_notes (
    id                 INTEGER     PRIMARY KEY AUTOINCREMENT,
    report_id          BIGINT      NOT NULL REFERENCES reports (id) ON DELETE CASCADE,
    source             VARCHAR(16) NOT NULL,
    author_uuid        CHAR(36),
    author_discord     BIGINT,
    author_name        VARCHAR(64) NOT NULL,
    body               TEXT        NOT NULL,
    discord_message_id BIGINT      UNIQUE,
    created_at         BIGINT      NOT NULL
);
CREATE INDEX idx_notes_report ON report_notes (report_id, created_at);

CREATE TABLE audit_log (
    id            INTEGER     PRIMARY KEY AUTOINCREMENT,
    report_id     BIGINT,
    action        VARCHAR(32) NOT NULL,
    source        VARCHAR(16) NOT NULL,
    actor_uuid    CHAR(36),
    actor_discord BIGINT,
    actor_name    VARCHAR(64) NOT NULL,
    detail        TEXT        NOT NULL,
    created_at    BIGINT      NOT NULL
);
CREATE INDEX idx_audit_report ON audit_log (report_id, created_at);
CREATE INDEX idx_audit_time ON audit_log (created_at);

CREATE TABLE account_links (
    mc_uuid    CHAR(36)    NOT NULL PRIMARY KEY,
    mc_name    VARCHAR(32) NOT NULL,
    discord_id BIGINT      NOT NULL UNIQUE,
    linked_at  BIGINT      NOT NULL
);
