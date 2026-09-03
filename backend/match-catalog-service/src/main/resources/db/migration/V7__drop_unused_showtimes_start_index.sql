-- ============================================================
-- V7: drop idx_showtimes_start, which nothing has ever read
--
-- start_time appears in exactly two queries. existsShowtimeAtVenueAndTime
-- filters on (venue_id, start_time), which V4's uk_showtimes_venue_time serves
-- with venue_id leading; findShowtimesByMatchIds only ORDERs BY start_time
-- inside a `match_id IN (...)` result, which is a sort of an already-selected
-- row set, not an index scan on start_time.
--
-- idx_showtimes_match stays -- it is declared on ShowtimeJpaEntity and does back
-- findShowtimesByMatchIds.
-- ============================================================

DROP INDEX IF EXISTS idx_showtimes_start;
