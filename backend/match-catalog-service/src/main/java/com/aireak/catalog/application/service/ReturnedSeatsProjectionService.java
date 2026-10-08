package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.ApplyReturnedSeatsUseCase;
import com.aireak.catalog.application.port.out.SeatAvailabilityProjectionPort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort.IncrementResult;
import com.aireak.catalog.application.port.out.ShowtimeSeatCountPort;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Applies seats a cancelled paid booking put back on sale to both places the catalog keeps
 * availability — the dual write of a return, mirroring {@link SoldSeatsProjectionService}'s of a
 * sale.
 *
 * <p><b>Order: Postgres first, Redis second — the reverse of the sale, and for the same reason.</b>
 * <pre>
 *   1. Postgres increment  — idempotent by event id, transactional, capped at total_seats
 *   2. Redis increment     — ONE Lua script, atomic, never recreates a missing key
 *   3. reseed              — Redis re-derived from Postgres whenever 2 was not clean, or 1 was a repeat
 * </pre>
 * Whatever goes wrong between the two steps, the number customers are shown must err LOW, never
 * high: a counter showing more seats than exist sells seats that do not. A sale therefore takes
 * seats off Redis first, and a return adds them to Postgres first. Fail between the two — a crash,
 * a Redis outage — and here Redis is short by the returned seats until the next reseed; with the
 * order the other way round it would be over, and a redelivery would add the seats to Redis a
 * second time before Postgres recognised the event id.
 *
 * <p><b>Why a repeat reseeds rather than undoes.</b> When Postgres reports the event already applied,
 * this service cannot know whether the earlier attempt reached step 2 before it failed. Adding
 * again might count the seats twice; skipping might leave them uncounted. Re-deriving the counter
 * from the row Postgres committed is right either way.
 *
 * <p><b>Why the reseed is safe here.</b> It is a read-then-write across two systems, which is only
 * safe while nothing else changes this showtime's counter in between. Returns are published on the
 * sale topic, keyed by showtimeId ({@code KafkaTopics#SEATS_RETURNED}), so the one consumer that
 * applies a showtime's sales applies its returns too, one event at a time — the guarantee
 * {@link SoldSeatsProjectionService}'s own reconcile already rests on.
 *
 * <p>Metrics: {@code catalog.seat_return_projection{outcome}} for throughput and failures, and the
 * shared {@code catalog.seat_counter.drift{reason}} whenever Redis had to be re-derived.
 */
@Slf4j
@Service
public class ReturnedSeatsProjectionService implements ApplyReturnedSeatsUseCase {

    private static final String PROJECTION_METRIC = "catalog.seat_return_projection";
    private static final String DRIFT_METRIC = "catalog.seat_counter.drift";

    private static final String OUTCOME_APPLIED = "applied";
    private static final String OUTCOME_DUPLICATE = "duplicate";
    private static final String OUTCOME_FAILED = "failed";

    private final SeatCounterUpdatePort seatCounterUpdatePort;
    private final SeatAvailabilityProjectionPort seatAvailabilityProjectionPort;
    private final ShowtimeSeatCountPort showtimeSeatCountPort;
    private final MeterRegistry meterRegistry;

    public ReturnedSeatsProjectionService(SeatCounterUpdatePort seatCounterUpdatePort,
                                          SeatAvailabilityProjectionPort seatAvailabilityProjectionPort,
                                          ShowtimeSeatCountPort showtimeSeatCountPort,
                                          MeterRegistry meterRegistry) {
        this.seatCounterUpdatePort = seatCounterUpdatePort;
        this.seatAvailabilityProjectionPort = seatAvailabilityProjectionPort;
        this.showtimeSeatCountPort = showtimeSeatCountPort;
        this.meterRegistry = meterRegistry;
        // At zero from the first scrape, for the reason SoldSeatsProjectionService#registerMetersAtZero gives.
        List.of(OUTCOME_APPLIED, OUTCOME_DUPLICATE, OUTCOME_FAILED)
                .forEach(outcome -> meterRegistry.counter(PROJECTION_METRIC, "outcome", outcome));
    }

    @Override
    public boolean applyReturnedSeats(String eventId, String showtimeId, int returnedSeatCount) {
        boolean applied;
        try {
            applied = seatAvailabilityProjectionPort.incrementAvailableSeats(eventId, showtimeId, returnedSeatCount);
        } catch (RuntimeException e) {
            // Nothing reached Redis yet, so there is nothing to undo: the event is retried whole.
            log.error("Returned-seat projection failed in Postgres, Redis left untouched for the retry: "
                    + "eventId={}, showtime={}, seats={}", eventId, showtimeId, returnedSeatCount, e);
            recordOutcome(OUTCOME_FAILED);
            throw e;
        }

        if (!applied) {
            log.info("Returned-seat event had already been projected, re-deriving the live counter instead of "
                    + "adding again: eventId={}, showtime={}, seats={}", eventId, showtimeId, returnedSeatCount);
            recordOutcome(OUTCOME_DUPLICATE);
            reseedFromDatabase(showtimeId, "duplicate");
            return false;
        }

        IncrementResult redisLeg = seatCounterUpdatePort.increment(showtimeId, returnedSeatCount);
        log.info("Returned-seat projection applied: eventId={}, showtime={}, seats={}, postgres=applied, redis={}, "
                + "liveCounter={}", eventId, showtimeId, returnedSeatCount, redisLeg.status(), redisLeg.availableSeats());
        recordOutcome(OUTCOME_APPLIED);
        if (!redisLeg.isClean()) {
            meterRegistry.counter(DRIFT_METRIC, "reason", redisLeg.status().name().toLowerCase(Locale.ROOT)).increment();
            reseedFromDatabase(showtimeId, redisLeg.status().name());
        }
        return true;
    }

    private void reseedFromDatabase(String showtimeId, String cause) {
        showtimeSeatCountPort.findAvailableSeats(showtimeId).ifPresentOrElse(
                availableSeats -> {
                    log.warn("Live seat counter re-derived from Postgres after a seat return: showtime={}, cause={}, "
                            + "postgresAvailableSeats={}", showtimeId, cause, availableSeats);
                    seatCounterUpdatePort.reseed(showtimeId, availableSeats);
                },
                () -> log.error("Cannot reseed the live seat counter, the showtime has no row in Postgres: "
                        + "showtime={}, cause={}", showtimeId, cause));
    }

    private void recordOutcome(String outcome) {
        meterRegistry.counter(PROJECTION_METRIC, "outcome", outcome).increment();
    }
}
