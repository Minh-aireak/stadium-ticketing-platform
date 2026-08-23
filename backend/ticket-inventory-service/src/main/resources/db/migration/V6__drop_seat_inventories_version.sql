-- ============================================================
-- V6: drop the unused optimistic-lock column on seat_inventories
-- ============================================================

-- seat_inventories has no mutable column: a row is inserted when the seat map is
-- generated and never updated. Selling a seat writes to seats, a different table,
-- which JPA does not treat as a change to the owning entity — so this version
-- never incremented and the optimistic lock it was meant to provide never engaged.
-- Concurrency on the confirm path is held by the Redisson per-showtime lock and by
-- Seat#sell rejecting a seat already SOLD by another booking.
ALTER TABLE seat_inventories DROP COLUMN IF EXISTS version;
