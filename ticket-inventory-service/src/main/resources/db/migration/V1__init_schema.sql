-- ============================================================
-- V1: ticket-inventory-service schema
-- ============================================================

CREATE SEQUENCE IF NOT EXISTS seat_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS seat_inventories (
    showtime_id VARCHAR(36)  PRIMARY KEY,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS seats (
    id                       BIGINT       PRIMARY KEY DEFAULT nextval('seat_id_seq'),
    showtime_id              VARCHAR(36)  NOT NULL,
    seat_code                VARCHAR(10)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    reserved_by_booking_id   VARCHAR(36),

    CONSTRAINT fk_seats_showtime
        FOREIGN KEY (showtime_id)
        REFERENCES seat_inventories (showtime_id)
        ON DELETE CASCADE,

    CONSTRAINT uk_seats_showtime_code
        UNIQUE (showtime_id, seat_code)
);

CREATE INDEX IF NOT EXISTS idx_seats_showtime ON seats (showtime_id);
CREATE INDEX IF NOT EXISTS idx_seats_status   ON seats (showtime_id, status);
