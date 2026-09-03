-- ============================================================
-- V7: drop idx_seats_status, which nothing has ever read
--
-- The two ways `seats` is read are SeatJpaRepository#findByShowtimeIdAndSeatCodeIn
-- and the eager @OneToMany on SeatInventoryJpaEntity; both filter on showtime_id
-- (plus seat_code), and uk_seats_showtime_code already serves them. Nothing
-- filters on `status` -- seat state is decided from the Redis holds and from
-- Seat#sell, not by querying for seats in a given status.
--
-- This is the hottest write table in the platform: every confirmed booking
-- updates one row per seat sold, and each of those updates has been maintaining
-- this index for no reader. V4 dropped idx_seats_showtime from the same table
-- for the neighbouring reason (redundant prefix); this one is simply unread.
-- ============================================================

DROP INDEX IF EXISTS idx_seats_status;
