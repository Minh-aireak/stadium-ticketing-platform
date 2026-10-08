package com.aireak.catalog.adapter.out.cache;

import com.aireak.catalog.application.port.out.SeatCapacityQueryPort;
import com.aireak.catalog.application.port.out.SeatCounterSeedPort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort;
import com.aireak.catalog.domain.model.SeatCapacityCheck;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redisson-backed implementation of all three live-seat-counter ports: the only writer of
 * {@code catalog:seats:{showtimeId}}.
 *
 * <p>Three interfaces, one class. The ports are split so each caller depends on the one operation
 * it needs (see {@link SeatCounterUpdatePort}); they land in a single adapter because the counter
 * having exactly one writer is the invariant the atomicity below rests on, and splitting the
 * implementation would put that invariant in more than one place.
 *
 * <p><b>Why Lua.</b> Redis executes an {@code EVAL} to completion before it serves any other
 * command, so everything inside one script is a single atomic step from every other client's point
 * of view. That is what makes {@link #decrement} safe under concurrency: read, compare, floor and
 * write happen with no window in between for a second projection to interleave. Doing the same
 * thing as separate {@code GET} and {@code SET} calls — which is how this counter used to be
 * maintained — leaves that window open, and the slower caller's {@code SET} silently reinstates a
 * count that another one had already superseded. That the old shape held up in practice was down to
 * one consumer thread per showtime; nothing here depends on that any more.
 *
 * <p>{@code DECRBY} alone would be atomic too, but it cannot express the floor: it would drive the
 * counter negative on a disagreement with Postgres, and a negative seat count reaches customers as
 * a nonsense number. The script clamps at zero and reports that it had to, which is both a correct
 * value and the signal the caller uses to reconcile.
 *
 * <p>{@link #check} and {@link #reseed} are deliberately NOT scripts — a lone {@code GET} and a
 * lone {@code SET} are already single commands, and wrapping either in {@code EVAL} would buy no
 * atomicity that Redis does not already give.
 *
 * <p><b>Failure policy.</b> Every operation fails open: a Redis failure is logged and reported
 * through the return value, never thrown. Postgres holds the durable count, so a blind counter
 * costs accuracy in what customers are shown, and must not cost the sale itself. The catches are
 * {@code RuntimeException} rather than {@code RedisException} deliberately — a timeout, a client
 * shutdown and a pool failure are not all the same type, and a fail-open component that only
 * fails open for some of them is not fail-open. Argument validation still throws, since a caller
 * asking to decrement zero or negative seats is a bug, not an outage.
 *
 * <p><b>TTL.</b> The counter is seeded when a showtime is created and refreshed on every write, so
 * under normal operation the TTL never fires. It is a backstop against keys for showtimes that came
 * and went: a counter that stops being written eventually disappears, and the first write after
 * that reports {@link SeatCounterUpdatePort.DecrementStatus#NOT_INITIALIZED} so the caller
 * re-derives it from Postgres. A showtime created further ahead than this TTL simply takes that
 * recovery path on its first sale.
 */
@Slf4j
@Component
public class RedisSeatAvailabilityCounter
        implements SeatCounterSeedPort, SeatCapacityQueryPort, SeatCounterUpdatePort {

    /**
     * Seeds only into an absent key. The read-then-write is inside the script rather than around it
     * so a second initialize racing the first cannot slip between the two and overwrite.
     */
    private static final String INITIALIZE_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if current then
              return 'ALREADY_PRESENT:' .. current
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 'SEEDED:' .. ARGV[1]
            """;

    /**
     * The atomic core of this class. Returns {@code status:remaining:deducted} — deducted is
     * reported separately from the request because a clamped decrement takes less than it was asked
     * for, and only the amount actually taken can be handed back by {@link #restore}.
     */
    private static final String DECREMENT_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 'NOT_INITIALIZED:0:0'
            end
            local available = tonumber(current)
            if available == nil then
              return 'CORRUPT:0:0'
            end
            local requested = tonumber(ARGV[1])
            local status = 'APPLIED'
            local deducted = requested
            local remaining = available - requested
            if remaining < 0 then
              status = 'CLAMPED'
              deducted = available
              remaining = 0
            end
            redis.call('SET', KEYS[1], string.format('%d', remaining), 'PX', ARGV[2])
            return string.format('%s:%d:%d', status, remaining, deducted)
            """;

    /**
     * Compensation for a decrement whose database leg failed. Refuses to recreate a key that is no
     * longer there: an absent counter means the TTL fired or someone flushed it, and inventing a
     * value out of a compensation amount alone would be worse than letting the reseed path
     * re-derive it from Postgres.
     */
    private static final String RESTORE_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 'NOT_INITIALIZED:0'
            end
            local available = tonumber(current)
            if available == nil then
              return 'CORRUPT:0'
            end
            local restored = available + tonumber(ARGV[1])
            redis.call('SET', KEYS[1], string.format('%d', restored), 'PX', ARGV[2])
            return string.format('RESTORED:%d', restored)
            """;

    private final RedissonClient redissonClient;
    private final LocalSeatCountCache localSeatCountCache;
    private final Duration counterTtl;

    public RedisSeatAvailabilityCounter(
            RedissonClient redissonClient,
            LocalSeatCountCache localSeatCountCache,
            @Value("${catalog.live-seats.counter-ttl:7d}") Duration counterTtl) {
        if (counterTtl.isZero() || counterTtl.isNegative()) {
            // PX 0 is an error reply, so a misconfigured TTL would fail every single write at
            // runtime rather than at startup. Fail here instead, where the cause is obvious.
            throw new IllegalArgumentException("catalog.live-seats.counter-ttl must be positive: " + counterTtl);
        }
        this.redissonClient = redissonClient;
        this.localSeatCountCache = localSeatCountCache;
        this.counterTtl = counterTtl;
    }

    // ----------------------------------------------------------------
    // Seeding
    // ----------------------------------------------------------------

    @Override
    public SeedResult initialize(String showtimeId, int totalSeats) {
        requirePositive(totalSeats, "totalSeats");
        String reply = eval(INITIALIZE_SCRIPT, showtimeId, String.valueOf(totalSeats));
        if (reply == null) {
            return SeedResult.UNAVAILABLE;
        }

        localSeatCountCache.invalidate(showtimeId);
        if (reply.startsWith("SEEDED:")) {
            log.debug("Seeded live seat counter: showtime={}, totalSeats={}, ttl={}",
                    showtimeId, totalSeats, counterTtl);
            return SeedResult.SEEDED;
        }
        log.warn("Live seat counter already existed at initialization, left untouched: "
                        + "showtime={}, existing={}, totalSeats={}",
                showtimeId, valuePart(reply), totalSeats);
        return SeedResult.ALREADY_PRESENT;
    }

    @Override
    public void reseed(String showtimeId, int availableSeats) {
        if (availableSeats < 0) {
            throw new IllegalArgumentException("availableSeats must not be negative: " + availableSeats);
        }
        try {
            counterBucket(showtimeId).set(String.valueOf(availableSeats), counterTtl);
            localSeatCountCache.invalidate(showtimeId);
            log.info("Live seat counter reseeded from Postgres: showtime={}, availableSeats={}",
                    showtimeId, availableSeats);
        } catch (RuntimeException e) {
            log.error("Failed to reseed live seat counter, Redis stays stale until the next write: "
                    + "showtime={}, availableSeats={}", showtimeId, availableSeats, e);
        }
    }

    // ----------------------------------------------------------------
    // Reading
    // ----------------------------------------------------------------

    @Override
    public SeatCapacityCheck check(String showtimeId, int requestedSeats) {
        requirePositive(requestedSeats, "requestedSeats");

        String raw;
        try {
            raw = counterBucket(showtimeId).get();
        } catch (RuntimeException e) {
            log.warn("Redis read failed during a seat capacity check, treating availability as unknown: "
                    + "showtime={}, requested={}, error={}", showtimeId, requestedSeats, e.getMessage());
            return SeatCapacityCheck.unknown(showtimeId, requestedSeats);
        }
        if (raw == null) {
            return SeatCapacityCheck.unknown(showtimeId, requestedSeats);
        }
        try {
            return SeatCapacityCheck.of(showtimeId, requestedSeats, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            log.warn("Live seat counter holds an unparseable value, treating availability as unknown: "
                    + "showtime={}, value={}", showtimeId, raw);
            return SeatCapacityCheck.unknown(showtimeId, requestedSeats);
        }
    }

    // ----------------------------------------------------------------
    // Mutating
    // ----------------------------------------------------------------

    @Override
    public DecrementResult decrement(String showtimeId, int seatCount) {
        requirePositive(seatCount, "seatCount");
        String reply = eval(DECREMENT_SCRIPT, showtimeId, String.valueOf(seatCount));
        if (reply == null) {
            return DecrementResult.nothingWritten(DecrementStatus.UNAVAILABLE);
        }

        String[] parts = reply.split(":", 3);
        DecrementStatus status = parseStatus(parts[0], showtimeId, reply);
        if (status == DecrementStatus.NOT_INITIALIZED || status == DecrementStatus.CORRUPT) {
            return DecrementResult.nothingWritten(status);
        }

        localSeatCountCache.invalidate(showtimeId);
        int remaining = Integer.parseInt(parts[1]);
        int deducted = Integer.parseInt(parts[2]);
        log.debug("Live seat counter decremented atomically: showtime={}, requested={}, deducted={}, remaining={}",
                showtimeId, seatCount, deducted, remaining);
        return new DecrementResult(status, remaining, deducted);
    }

    @Override
    public void restore(String showtimeId, int seatCount) {
        requirePositive(seatCount, "seatCount");
        String reply = eval(RESTORE_SCRIPT, showtimeId, String.valueOf(seatCount));
        if (reply == null) {
            log.error("Could not restore seats to the live counter, Redis unreachable: showtime={}, seats={}",
                    showtimeId, seatCount);
            return;
        }
        if (!reply.startsWith("RESTORED:")) {
            log.warn("Nothing to restore seats onto, the counter will be re-derived from Postgres: "
                    + "showtime={}, seats={}, reason={}", showtimeId, seatCount, reply.split(":", 2)[0]);
            return;
        }
        localSeatCountCache.invalidate(showtimeId);
        log.info("Restored seats to the live counter after a failed projection: showtime={}, seats={}, counter={}",
                showtimeId, seatCount, valuePart(reply));
    }

    /**
     * {@link #RESTORE_SCRIPT} again, deliberately: adding seats back atomically and refusing to
     * invent a missing key is exactly what a projected return needs too. What differs is only what
     * the caller does with the answer, so this reports it instead of just logging it.
     */
    @Override
    public IncrementResult increment(String showtimeId, int seatCount) {
        requirePositive(seatCount, "seatCount");
        String reply = eval(RESTORE_SCRIPT, showtimeId, String.valueOf(seatCount));
        if (reply == null) {
            return IncrementResult.nothingWritten(IncrementStatus.UNAVAILABLE);
        }
        if (reply.startsWith("NOT_INITIALIZED")) {
            log.warn("No live seat counter to add returned seats to, it will be seeded from Postgres: showtime={}",
                    showtimeId);
            return IncrementResult.nothingWritten(IncrementStatus.NOT_INITIALIZED);
        }
        if (!reply.startsWith("RESTORED:")) {
            log.error("Live seat counter holds a non-numeric value, it will be reseeded from Postgres: showtime={}",
                    showtimeId);
            return IncrementResult.nothingWritten(IncrementStatus.CORRUPT);
        }
        localSeatCountCache.invalidate(showtimeId);
        int available = Integer.parseInt(valuePart(reply));
        log.debug("Live seat counter incremented atomically: showtime={}, added={}, available={}",
                showtimeId, seatCount, available);
        return new IncrementResult(IncrementStatus.APPLIED, available);
    }

    // ----------------------------------------------------------------
    // Redis plumbing
    // ----------------------------------------------------------------

    /** @return the script's reply, or {@code null} when Redis could not be reached. */
    private String eval(String script, String showtimeId, String argument) {
        try {
            return redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    script,
                    RScript.ReturnType.VALUE,
                    List.of(LiveSeatKeys.of(showtimeId)),
                    argument, String.valueOf(counterTtl.toMillis()));
        } catch (RuntimeException e) {
            log.error("Live seat counter script failed, Postgres stays authoritative: showtime={}, arg={}, error={}",
                    showtimeId, argument, e.getMessage());
            return null;
        }
    }

    private DecrementStatus parseStatus(String status, String showtimeId, String reply) {
        return switch (status) {
            case "APPLIED" -> DecrementStatus.APPLIED;
            case "CLAMPED" -> {
                log.error("Live seat counter hit its floor: Redis held fewer seats than the sale being "
                                + "projected, so it disagreed with Postgres and will be reseeded. showtime={}, reply={}",
                        showtimeId, reply);
                yield DecrementStatus.CLAMPED;
            }
            case "NOT_INITIALIZED" -> {
                log.warn("No live seat counter to decrement, it will be seeded from Postgres: showtime={}",
                        showtimeId);
                yield DecrementStatus.NOT_INITIALIZED;
            }
            default -> {
                log.error("Live seat counter holds a non-numeric value, it will be reseeded from Postgres: "
                        + "showtime={}", showtimeId);
                yield DecrementStatus.CORRUPT;
            }
        };
    }

    // StringCodec, not Redisson's default binary codec: the scripts above call tonumber() on the
    // stored bytes, so the value has to be the digits themselves. See LiveSeatKeys.
    private RBucket<String> counterBucket(String showtimeId) {
        return redissonClient.getBucket(LiveSeatKeys.of(showtimeId), StringCodec.INSTANCE);
    }

    private static String valuePart(String reply) {
        String[] parts = reply.split(":", 2);
        return parts.length > 1 ? parts[1] : reply;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
