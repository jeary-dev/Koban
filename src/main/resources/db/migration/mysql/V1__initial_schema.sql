-- Koban schema v1 (MySQL 8 / MariaDB 10.6+). Timestamps are epoch milliseconds; UUIDs are text.

CREATE TABLE players (
    uuid       CHAR(36)    NOT NULL PRIMARY KEY,
    name       VARCHAR(32) NOT NULL,
    name_lower VARCHAR(32) NOT NULL,
    last_seen  BIGINT      NOT NULL,
    INDEX idx_players_name (name_lower)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE reports (
    id                 BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
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
    version            INT         NOT NULL DEFAULT 0,
    INDEX idx_reports_status (status, last_reported_at),
    INDEX idx_reports_reported (reported_uuid, created_at),
    INDEX idx_reports_dirty (discord_dirty)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE report_reporters (
    report_id     BIGINT       NOT NULL,
    reporter_uuid CHAR(36)     NOT NULL,
    reporter_name VARCHAR(32)  NOT NULL,
    category      VARCHAR(64)  NOT NULL,
    details       VARCHAR(512),
    reported_at   BIGINT       NOT NULL,
    feedback      VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    PRIMARY KEY (report_id, reporter_uuid),
    INDEX idx_reporters_reporter (reporter_uuid, feedback),
    CONSTRAINT fk_reporters_report FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE evidence (
    id            BIGINT     NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id     BIGINT     NOT NULL,
    reporter_uuid CHAR(36)   NOT NULL,
    captured_at   BIGINT     NOT NULL,
    format        INT        NOT NULL,
    payload       MEDIUMTEXT NOT NULL,
    INDEX idx_evidence_report (report_id),
    CONSTRAINT fk_evidence_report FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE report_notes (
    id                 BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id          BIGINT      NOT NULL,
    source             VARCHAR(16) NOT NULL,
    author_uuid        CHAR(36),
    author_discord     BIGINT,
    author_name        VARCHAR(64) NOT NULL,
    body               TEXT        NOT NULL,
    discord_message_id BIGINT,
    created_at         BIGINT      NOT NULL,
    UNIQUE KEY uq_notes_discord_message (discord_message_id),
    INDEX idx_notes_report (report_id, created_at),
    CONSTRAINT fk_notes_report FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE audit_log (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id     BIGINT,
    action        VARCHAR(32) NOT NULL,
    source        VARCHAR(16) NOT NULL,
    actor_uuid    CHAR(36),
    actor_discord BIGINT,
    actor_name    VARCHAR(64) NOT NULL,
    detail        TEXT        NOT NULL,
    created_at    BIGINT      NOT NULL,
    INDEX idx_audit_report (report_id, created_at),
    INDEX idx_audit_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE account_links (
    mc_uuid    CHAR(36)    NOT NULL PRIMARY KEY,
    mc_name    VARCHAR(32) NOT NULL,
    discord_id BIGINT      NOT NULL,
    linked_at  BIGINT      NOT NULL,
    UNIQUE KEY uq_links_discord (discord_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
