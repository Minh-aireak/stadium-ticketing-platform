-- ============================================================
-- V4: notifications.body holds the rendered plain-text email body, and that body is not
-- length-bounded. The booking-confirmed template interpolates the booking's whole seat list,
-- and nothing in booking-service or ticket-inventory-service caps how many seats one booking
-- may hold (both validate only @NotEmpty), so a large group booking crosses VARCHAR(2000) at
-- roughly 230 seats.
--
-- The failure was not a truncation: the insert raised DataIntegrityViolationException, which
-- rolled back the dispatch transaction AFTER the email had already been sent. Kafka then
-- redelivered, re-sending the same confirmation on every retry before the record finally
-- landed on a dead-letter topic. TEXT removes the ceiling entirely; Postgres stores TEXT and
-- VARCHAR identically, so this costs nothing.
--
-- (Deliberately no FreeMarker interpolation syntax written out above: Flyway resolves a
-- dollar-brace placeholder even inside a comment and fails the migration.)
-- ============================================================

ALTER TABLE notifications
    ALTER COLUMN body TYPE TEXT;
