-- ============================================================
-- V1: match-catalog-service schema
-- ============================================================

CREATE TABLE IF NOT EXISTS matches (
    match_id    VARCHAR(36)  PRIMARY KEY,
    home_team   VARCHAR(100) NOT NULL,
    away_team   VARCHAR(100) NOT NULL,
    competition VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS showtimes (
    showtime_id      VARCHAR(36) PRIMARY KEY,
    match_id         VARCHAR(36) NOT NULL,
    start_time       TIMESTAMP   NOT NULL,
    venue_id         VARCHAR(36) NOT NULL,
    total_seats      INT         NOT NULL,
    available_seats  INT         NOT NULL,

    CONSTRAINT fk_showtimes_match
        FOREIGN KEY (match_id)
        REFERENCES matches (match_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_matches_status     ON matches (status);
CREATE INDEX IF NOT EXISTS idx_showtimes_match    ON showtimes (match_id);
CREATE INDEX IF NOT EXISTS idx_showtimes_start    ON showtimes (start_time);
