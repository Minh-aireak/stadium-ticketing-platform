-- ============================================================
-- V1: booking-service schema
-- ============================================================

CREATE TABLE IF NOT EXISTS bookings (
    booking_id   VARCHAR(36)     PRIMARY KEY,
    customer_id  VARCHAR(36)     NOT NULL,
    showtime_id  VARCHAR(36)     NOT NULL,
    seat_codes   VARCHAR(1000)   NOT NULL,
    amount       NUMERIC(15, 2)  NOT NULL,
    currency     CHAR(3)         NOT NULL,
    status       VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    created_at   TIMESTAMP       NOT NULL,
    version      BIGINT          NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bookings_customer    ON bookings (customer_id);
CREATE INDEX IF NOT EXISTS idx_bookings_showtime    ON bookings (showtime_id);
CREATE INDEX IF NOT EXISTS idx_bookings_status      ON bookings (status);
