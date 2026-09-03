package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.ApplySoldSeatsUseCase;
import com.aireak.catalog.application.port.in.CheckSeatCapacityUseCase;
import com.aireak.catalog.application.port.out.SeatAvailabilityProjectionPort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort.DecrementResult;
import com.aireak.catalog.application.port.out.ShowtimeSeatCountPort;
import com.aireak.catalog.domain.model.SeatCapacityCheck;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Applies a completed seat sale to both places the catalog keeps availability: the live Redis
 * counter customers are shown, and the durable {@code showtimes.available_seats} column.
 *
 * <p><b>Order, and why.</b> Redis first, Postgres second:
 * <pre>
 *   1. capacity pre-flight  — read-only, catches an over-capacity sale before anything moves
 *   2. Redis decrement      — ONE Lua script: read, floor, write, atomically
 *   3. Postgres decrement   — idempotent, transactional, the durable record
 *   4. reconcile / undo     — whichever the outcome of 2 and 3 calls for
 * </pre>
 * Step 2 is where the correctness of this whole path lives. The counter used to be maintained as
 * "update Postgres, read the new total back, overwrite Redis with it" — a read-modify-write with no
 * atomicity across the two systems. What kept that correct was not the code but the deployment: one
 * consumer thread per showtime, so the two halves never interleaved. The script needs no such luck.
 * Redis runs it to completion before serving anyone else, so every decrement observes the one
 * before it no matter how many threads, pods or partitions are involved.
 *
 * <p><b>This projects a sale, it does not authorize one.</b> The seats were already sold in
 * ticket-inventory-service by the time the event arrives, so nothing here may refuse the decrement
 * — hence the floor at zero rather than a rejection. A floor that engages means Redis was holding
 * fewer seats than Postgres, which is a disagreement worth an alert and a reseed, not a refusal.
 *
 * <p><b>The two legs can still disagree, and every way they can is handled.</b> Postgres write
 * fails: {@link #undoRedisLeg} hands back exactly what the script deducted. Event turns out to be
 * a duplicate and Postgres skips it: the same undo runs, because the Redis leg had no such
 * idempotency check to skip on. Redis was unreachable, its key had expired, or its value drifted:
 * {@link #reconcileFromDatabase} re-derives the counter from the row Postgres just committed. The
 * only residue is a sub-millisecond window during an undo where the count reads low — visible as
 * fewer seats, never as more.
 *
 * <p><b>Every one of those branches is also a metric</b>, because a disagreement that exists only
 * as a log line can only be alerted on from the log pipeline. See {@link #recordOutcome} for the
 * meters and what each one means when it moves.
 *
 * <p><b>The one thing that is NOT atomic, and why that is acceptable.</b>
 * {@link #reconcileFromDatabase} reads Postgres and then writes Redis — the same read-modify-write
 * shape the decrement was rewritten to avoid, and it cannot be a script because the value comes
 * from another system. Two reconciles for the SAME showtime overlapping would let the slower one
 * write a value the faster one had already superseded. That cannot happen here: sold-seat events
 * are keyed by {@code showtimeId} (see ticket-inventory-service's outbox publisher), so Kafka puts
 * every event for one showtime on one partition, and one consumer processes them in order. The
 * reconcile is safe because of that key, not because of anything in this class — which is exactly
 * why the decrement was NOT left resting on the same assumption. It runs on every sale, it is the
 * number customers see, and a brief double-assignment during a consumer-group rebalance is enough
 * to corrupt it. The reconcile runs only on the branches that already went wrong, and a rebalance
 * landing inside that window leaves a drift the next such branch repairs.
 *
 * <p><b>Not {@code @Transactional}, on purpose.</b> {@code SeatAvailabilityProjectionAdapter} owns
 * the transaction, so by the time it returns the row is committed and
 * {@link ShowtimeSeatCountPort#findAvailableSeats} reads the value the reconcile actually needs.
 * Wrapping this method in a transaction would make that read see the projection's own uncommitted
 * state and reseed Redis from a number Postgres might still roll back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SoldSeatsProjectionService implements ApplySoldSeatsUseCase {

    private static final String PROJECTION_METRIC = "catalog.seat_projection";
    private static final String OVERSELL_METRIC = "catalog.seat_counter.oversell";
    private static final String DRIFT_METRIC = "catalog.seat_counter.drift";

    private final CheckSeatCapacityUseCase seatCapacityCheck;
    private final SeatCounterUpdatePort seatCounterUpdatePort;
    private final SeatAvailabilityProjectionPort seatAvailabilityProjectionPort;
    private final ShowtimeSeatCountPort showtimeSeatCountPort;
    private final MeterRegistry meterRegistry;

    @Override
    public boolean applySoldSeats(String eventId, String showtimeId, int soldSeatCount) {
        // Read-only and non-binding — the decrement below is the authoritative gate. This runs to
        // catch the condition early: a sale larger than what Redis says is left is the first sign
        // of an oversell, and worth a metric of its own rather than only a log line.
        SeatCapacityCheck capacity = seatCapacityCheck.checkCapacity(showtimeId, soldSeatCount);
        if (capacity.exceedsCapacity()) {
            meterRegistry.counter(OVERSELL_METRIC).increment();
        }

        DecrementResult redisLeg = seatCounterUpdatePort.decrement(showtimeId, soldSeatCount);

        boolean applied;
        try {
            applied = seatAvailabilityProjectionPort.decrementAvailableSeats(eventId, showtimeId, soldSeatCount);
        } catch (RuntimeException e) {
            log.error("Sold-seat projection failed in Postgres, rolling the Redis counter back: "
                            + "eventId={}, showtime={}, seats={}, deductedFromRedis={}",
                    eventId, showtimeId, soldSeatCount, redisLeg.deductedSeats(), e);
            recordOutcome("failed");
            undoRedisLeg(showtimeId, redisLeg);
            throw e;
        }

        if (!applied) {
            // Postgres recognised the event id and skipped its decrement. The Redis leg has no such
            // record to check, so it went ahead and now has to be put back.
            log.info("Sold-seat event had already been projected, undoing the Redis decrement it repeated: "
                            + "eventId={}, showtime={}, seats={}",
                    eventId, showtimeId, soldSeatCount);
            recordOutcome("duplicate");
            undoRedisLeg(showtimeId, redisLeg);
            return false;
        }

        log.info("Sold-seat projection applied: eventId={}, showtime={}, seats={}, redis={}, remaining={}",
                eventId, showtimeId, soldSeatCount, redisLeg.status(), redisLeg.remainingSeats());
        recordOutcome("applied");
        reconcileFromDatabase(showtimeId, redisLeg);
        return true;
    }

    /**
     * Hands back exactly what the script took — {@link DecrementResult#deductedSeats()}, not the
     * requested count, since a clamped decrement took less than it was asked for.
     *
     * <p>A leg that never wrote (Redis down, key absent) has nothing to undo, and nothing else
     * happens to it either: both callers — the Postgres failure and the duplicate — leave this
     * method by throwing or returning, so {@link #reconcileFromDatabase} is NOT reached on either
     * branch. This javadoc used to say the reconcile covered them, which is only true of the
     * success path. The counter is left absent until the next real sale for that showtime reports
     * {@code NOT_INITIALIZED} and reseeds it — the same self-healing the TTL already relies on,
     * but that, not a reconcile here.
     */
    private void undoRedisLeg(String showtimeId, DecrementResult redisLeg) {
        if (!redisLeg.wrote()) {
            log.debug("Nothing to undo in Redis, the decrement never wrote: showtime={}, status={}",
                    showtimeId, redisLeg.status());
            return;
        }
        seatCounterUpdatePort.restore(showtimeId, redisLeg.deductedSeats());
    }

    /**
     * Re-derives the counter from the row Postgres just committed, for every outcome except a clean
     * decrement. This is the one path that repairs drift, so it covers all of: a key that expired
     * or was never seeded, a value that hit the floor because Redis had fallen behind, a value that
     * was not a number, and a Redis that was unreachable a moment ago and may be back now.
     *
     * <p>Costs one indexed read plus one {@code SET}, and only on the branches that already went
     * wrong — the clean path pays nothing. See the class javadoc for why this read-then-write is
     * safe without a script, and what it rests on.
     */
    private void reconcileFromDatabase(String showtimeId, DecrementResult redisLeg) {
        if (redisLeg.isClean()) {
            return;
        }
        meterRegistry.counter(DRIFT_METRIC, "reason", tagValue(redisLeg.status().name())).increment();
        showtimeSeatCountPort.findAvailableSeats(showtimeId).ifPresentOrElse(
                availableSeats -> {
                    log.warn("Live seat counter disagreed with Postgres after a projection, reseeding it: "
                                    + "showtime={}, redisStatus={}, postgresAvailableSeats={}",
                            showtimeId, redisLeg.status(), availableSeats);
                    seatCounterUpdatePort.reseed(showtimeId, availableSeats);
                },
                () -> log.error("Cannot reseed the live seat counter, the showtime has no row in Postgres: "
                        + "showtime={}, redisStatus={}", showtimeId, redisLeg.status()));
    }

    /**
     * Three meters, each answering a question a log search cannot answer cheaply:
     *
     * <ul>
     *   <li>{@code catalog.seat_projection{outcome}} — throughput and health of the projection.
     *       {@code failed} climbing means events are heading for the dead-letter topic (see
     *       {@code SeatsSoldDeadLetterConsumer}); a steady trickle of {@code duplicate} is normal
     *       Kafka at-least-once delivery, a sudden burst is a consumer that stopped committing
     *       offsets.</li>
     *   <li>{@code catalog.seat_counter.oversell} — sales arriving for more seats than the counter
     *       says are left. Any movement is worth looking at: either the counter is behind, or
     *       ticket-inventory-service sold seats it should not have.</li>
     *   <li>{@code catalog.seat_counter.drift{reason}} — Redis and Postgres disagreed and the
     *       counter had to be rebuilt. {@code not_initialized} after a Redis restart is expected
     *       and self-healing; {@code clamped} is not, and means the number customers were shown was
     *       wrong until this reseed corrected it.</li>
     * </ul>
     */
    private void recordOutcome(String outcome) {
        meterRegistry.counter(PROJECTION_METRIC, "outcome", outcome).increment();
    }

    // Prometheus convention is lower_snake_case label values; the enum constants are SCREAMING_SNAKE.
    private static String tagValue(String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
