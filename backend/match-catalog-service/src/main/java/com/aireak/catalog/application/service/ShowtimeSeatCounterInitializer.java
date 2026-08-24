package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.out.SeatAvailabilityCounterPort;
import com.aireak.catalog.application.port.out.SeatAvailabilityCounterPort.SeedResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Seeds a showtime's live seat counter in Redis the moment the showtime is created, so the counter
 * exists before anything ever needs to read or decrement it.
 *
 * <p><b>Why seed up front.</b> Without this, the counter only came into existence on the first
 * sale, and everything before that fell back to whatever seat count a cached Match happened to
 * carry. Seeding at creation makes the invariant simple and checkable: a showtime that exists has
 * a counter, so a missing counter always means something went wrong (expiry, a flush, a Redis
 * restart) rather than "no tickets sold yet" — and {@link SoldSeatsProjectionService} can treat
 * that case as the recovery path it is.
 *
 * <p><b>Fail-open, deliberately.</b> Creating a showtime must not depend on Redis being up. A
 * failed seed is logged and swallowed; the first sold-seat projection finds no counter and
 * re-derives it from Postgres, which is the same recovery the TTL already relies on.
 *
 * <p>Called from {@code MatchCatalogService#addShowtime} rather than off {@code ShowtimeAddedEvent}
 * on purpose: the counter has to be there for the browse endpoints as soon as the showtime is
 * visible, not one Kafka hop later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowtimeSeatCounterInitializer {

    private final SeatAvailabilityCounterPort seatAvailabilityCounterPort;

    public void initializeCounter(String showtimeId, int totalSeats) {
        SeedResult result;
        try {
            result = seatAvailabilityCounterPort.initialize(showtimeId, totalSeats);
        } catch (RuntimeException e) {
            log.error("Failed to seed the live seat counter for a new showtime, it will be derived from "
                            + "Postgres on the first sale: showtime={}, totalSeats={}",
                    showtimeId, totalSeats, e);
            return;
        }

        switch (result) {
            case SEEDED -> log.info("Live seat counter initialized: showtime={}, totalSeats={}",
                    showtimeId, totalSeats);
            // A brand-new showtime carries a freshly generated id, so a counter already sitting on
            // that key is not a duplicate creation — it is a leftover, or an id collision worth
            // knowing about. The adapter refuses to overwrite it either way.
            case ALREADY_PRESENT -> log.warn(
                    "A live seat counter already existed for a newly created showtime and was left as is: "
                            + "showtime={}, totalSeats={}", showtimeId, totalSeats);
            case UNAVAILABLE -> log.error(
                    "Redis was unreachable while initializing a live seat counter, it will be derived from "
                            + "Postgres on the first sale: showtime={}, totalSeats={}", showtimeId, totalSeats);
        }
    }
}
