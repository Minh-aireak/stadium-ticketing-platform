-- ============================================================
-- V4: drop redundant index on seats(showtime_id)
-- ============================================================

-- idx_seats_showtime is a strict column-prefix of uk_seats_showtime_code
-- (UNIQUE(showtime_id, seat_code)) — the unique constraint's own implicit
-- index already serves any lookup filtered by showtime_id alone.
DROP INDEX IF EXISTS idx_seats_showtime;
