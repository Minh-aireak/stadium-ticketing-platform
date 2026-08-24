package com.aireak.catalog.adapter.out.cache;

import com.aireak.catalog.application.port.out.SeatAvailabilityCounterPort.DecrementResult;
import com.aireak.catalog.application.port.out.SeatAvailabilityCounterPort.DecrementStatus;
import com.aireak.catalog.application.port.out.SeatAvailabilityCounterPort.SeedResult;
import com.aireak.catalog.domain.model.SeatCapacityCheck;
import com.aireak.catalog.domain.model.SeatCapacityCheck.Verdict;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Run against a real Redis, because the thing under test IS Redis's execution model: a Lua script
 * runs to completion before any other command is served. Nothing about that survives being mocked.
 *
 * <p>{@link #concurrentDecrementsNeverLoseAnUpdate} is the reason this class exists. It is the
 * scenario the old "read the total from Postgres, overwrite the Redis key with it" approach got
 * wrong — under overlapping sales the slower writer reinstated seats that were already gone, and
 * Redis ended up claiming inventory Postgres had sold.
 */
@Testcontainers
class RedisSeatAvailabilityCounterTest {

    private static final Duration COUNTER_TTL = Duration.ofMinutes(10);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static RedissonClient redissonClient;

    private final AtomicInteger localInvalidations = new AtomicInteger();
    private RedisSeatAvailabilityCounter counter;

    @BeforeAll
    static void startRedis() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void stopRedis() {
        redissonClient.shutdown();
    }

    @BeforeEach
    void setUp() {
        redissonClient.getKeys().flushdb();
        localInvalidations.set(0);
        counter = new RedisSeatAvailabilityCounter(
                redissonClient, showtimeId -> localInvalidations.incrementAndGet(), COUNTER_TTL);
    }

    // ----------------------------------------------------------------
    // Seeding
    // ----------------------------------------------------------------

    @Test
    void initializeSeedsAnAbsentCounterWithTheShowtimesFullCapacity() {
        assertThat(counter.initialize("showtime-1", 500)).isEqualTo(SeedResult.SEEDED);

        assertThat(rawCounter("showtime-1")).isEqualTo("500");
        assertThat(counterTtl("showtime-1")).isPositive();
    }

    /**
     * The guard that makes initialization safe to repeat. A replayed creation must never write full
     * capacity over a counter that has already sold seats — that would hand back inventory.
     */
    @Test
    void initializeLeavesAnExistingCounterAloneEvenWhenItHasAlreadySoldSeats() {
        counter.initialize("showtime-1", 500);
        counter.decrement("showtime-1", 120);

        assertThat(counter.initialize("showtime-1", 500)).isEqualTo(SeedResult.ALREADY_PRESENT);
        assertThat(rawCounter("showtime-1")).isEqualTo("380");
    }

    @Test
    void reseedOverwritesTheCounterWithTheValuePostgresHolds() {
        counter.initialize("showtime-1", 500);

        counter.reseed("showtime-1", 12);

        assertThat(rawCounter("showtime-1")).isEqualTo("12");
        assertThat(counterTtl("showtime-1")).isPositive();
    }

    // ----------------------------------------------------------------
    // Capacity check
    // ----------------------------------------------------------------

    @Test
    void checkComparesTheRequestAgainstWhatTheCounterHolds() {
        counter.initialize("showtime-1", 10);

        assertThat(counter.check("showtime-1", 10))
                .isEqualTo(new SeatCapacityCheck("showtime-1", 10, 10, Verdict.WITHIN_CAPACITY));

        SeatCapacityCheck tooMany = counter.check("showtime-1", 11);
        assertThat(tooMany.verdict()).isEqualTo(Verdict.EXCEEDS_CAPACITY);
        assertThat(tooMany.shortfall()).isEqualTo(1);
    }

    /** A missing counter is "we do not know", never "there is room" — see {@link SeatCapacityCheck}. */
    @Test
    void checkReportsUnknownRatherThanRoomWhenThereIsNoCounter() {
        SeatCapacityCheck check = counter.check("never-seeded", 4);

        assertThat(check.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(check.isKnown()).isFalse();
        assertThat(check.availableSeats()).isEqualTo(SeatCapacityCheck.UNKNOWN_AVAILABILITY);
    }

    @Test
    void checkDoesNotChangeTheCounter() {
        counter.initialize("showtime-1", 10);

        counter.check("showtime-1", 4);

        assertThat(rawCounter("showtime-1")).isEqualTo("10");
    }

    // ----------------------------------------------------------------
    // Decrement
    // ----------------------------------------------------------------

    @Test
    void decrementSubtractsTheSoldSeatsAndReportsWhatIsLeft() {
        counter.initialize("showtime-1", 100);

        DecrementResult result = counter.decrement("showtime-1", 3);

        assertThat(result).isEqualTo(new DecrementResult(DecrementStatus.APPLIED, 97, 3));
        assertThat(result.isClean()).isTrue();
        assertThat(rawCounter("showtime-1")).isEqualTo("97");
        assertThat(localInvalidations).hasValue(2); // the seed, then this decrement
    }

    /**
     * The sale being projected already happened, so the counter absorbs it rather than refusing it —
     * but it stops at zero instead of going negative, and says it had to, which is what makes the
     * caller reseed from Postgres.
     */
    @Test
    void decrementFloorsAtZeroAndReportsThatItHadTo() {
        counter.initialize("showtime-1", 2);

        DecrementResult result = counter.decrement("showtime-1", 5);

        assertThat(result).isEqualTo(new DecrementResult(DecrementStatus.CLAMPED, 0, 2));
        assertThat(result.isClean()).isFalse();
        assertThat(result.wrote()).isTrue();
        assertThat(rawCounter("showtime-1")).isEqualTo("0");
    }

    @Test
    void decrementOnAnAbsentCounterWritesNothingAndAsksToBeReseeded() {
        DecrementResult result = counter.decrement("never-seeded", 3);

        assertThat(result.status()).isEqualTo(DecrementStatus.NOT_INITIALIZED);
        assertThat(result.wrote()).isFalse();
        assertThat(rawCounter("never-seeded")).isNull();
    }

    @Test
    void decrementOnANonNumericCounterWritesNothingAndAsksToBeReseeded() {
        redissonClient.getBucket("catalog:seats:showtime-1", StringCodec.INSTANCE).set("not-a-number");

        DecrementResult result = counter.decrement("showtime-1", 3);

        assertThat(result.status()).isEqualTo(DecrementStatus.CORRUPT);
        assertThat(result.wrote()).isFalse();
        assertThat(rawCounter("showtime-1")).isEqualTo("not-a-number");
    }

    /**
     * 8 threads racing 800 single-seat decrements against a counter of exactly 800. Atomicity means
     * two things here, and both are asserted: the counter lands on exactly 0, and not one decrement
     * hit the floor — every thread saw the previous one's result. A read-modify-write would leave
     * the counter well above 0, having repeatedly written back a value another thread had already
     * superseded.
     */
    @Test
    void concurrentDecrementsNeverLoseAnUpdate() throws Exception {
        int threads = 8;
        int decrementsPerThread = 100;
        counter.initialize("showtime-1", threads * decrementsPerThread);

        List<DecrementResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        for (int i = 0; i < decrementsPerThread; i++) {
                            results.add(counter.decrement("showtime-1", 1));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(rawCounter("showtime-1")).isEqualTo("0");
        assertThat(results).hasSize(threads * decrementsPerThread)
                .allSatisfy(result -> assertThat(result.status()).isEqualTo(DecrementStatus.APPLIED));
        // Every decrement saw a distinct remaining value: 799 down to 0, none observed twice.
        assertThat(results.stream().map(DecrementResult::remainingSeats).distinct().count())
                .isEqualTo(threads * decrementsPerThread);
    }

    // ----------------------------------------------------------------
    // Restore
    // ----------------------------------------------------------------

    @Test
    void restoreHandsBackExactlyWhatADecrementTook() {
        counter.initialize("showtime-1", 100);
        DecrementResult decrement = counter.decrement("showtime-1", 4);

        counter.restore("showtime-1", decrement.deductedSeats());

        assertThat(rawCounter("showtime-1")).isEqualTo("100");
    }

    /** Nothing to restore onto: inventing a counter from a compensation amount would be worse than
     *  letting the reseed path re-derive it from Postgres. */
    @Test
    void restoreDoesNotRecreateACounterThatHasGoneAway() {
        counter.restore("never-seeded", 3);

        assertThat(rawCounter("never-seeded")).isNull();
    }

    // ----------------------------------------------------------------
    // Argument guards
    // ----------------------------------------------------------------

    @Test
    void rejectsNonPositiveSeatCountsRatherThanInflatingTheCounter() {
        counter.initialize("showtime-1", 100);

        assertThatThrownBy(() -> counter.decrement("showtime-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.decrement("showtime-1", -5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.check("showtime-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counter.initialize("showtime-2", 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(rawCounter("showtime-1")).isEqualTo("100");
    }

    private static String rawCounter(String showtimeId) {
        return (String) redissonClient.getBucket("catalog:seats:" + showtimeId, StringCodec.INSTANCE).get();
    }

    private static long counterTtl(String showtimeId) {
        return redissonClient.getBucket("catalog:seats:" + showtimeId, StringCodec.INSTANCE).remainTimeToLive();
    }
}
