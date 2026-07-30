-- ============================================================
-- V4: prevent two showtimes double-booking the same venue/time
-- ============================================================

-- MatchCatalogService#addShowtime does an application-level exists() check
-- before insert, but that's a check-then-act race under concurrent requests
-- (two calls can both pass the check before either commits). This unique
-- constraint is the actual concurrency-safe backstop; a violation surfaces
-- as DataIntegrityViolationException, already mapped to 409 Conflict by
-- GlobalExceptionHandler.
ALTER TABLE showtimes
    ADD CONSTRAINT uk_showtimes_venue_time UNIQUE (venue_id, start_time);
