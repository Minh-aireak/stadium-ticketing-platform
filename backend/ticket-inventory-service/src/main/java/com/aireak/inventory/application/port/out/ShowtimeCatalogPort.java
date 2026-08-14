package com.aireak.inventory.application.port.out;

import java.time.Instant;
import java.util.List;

public interface ShowtimeCatalogPort {
    void requireBookable(String showtimeId);

    /**
     * Remembers that these showtimes' match was cancelled or completed in catalog-service — a
     * permanent, never-revoked fact — so a subsequent {@link #requireBookable} for one of them
     * can reject locally without a network round trip. Called off catalog-service's
     * {@code MatchCancelledEvent}/{@code MatchCompletedEvent}.
     */
    void markUnbookable(List<String> showtimeIds);

    /**
     * Remembers a showtime's start time — set once at creation and never changed — so
     * {@link #requireBookable} can locally reject a reserve/hold for an already-started showtime
     * without a network round trip. Called off catalog-service's {@code ShowtimeAddedEvent}.
     */
    void rememberStartTime(String showtimeId, Instant startTime);
}
