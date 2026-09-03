package com.aireak.booking.adapter.out.cache;

import com.aireak.booking.application.port.out.IdempotencyClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@link RedissonIdempotencyStore} does when Redis will not answer — the half
 * {@code RedissonIdempotencyStoreTest} cannot cover, since it runs against a Redis that works.
 *
 * <p>{@link com.aireak.booking.application.port.out.IdempotencyStore} calls itself "a performance
 * layer, not the source of truth". {@code claim()} was written that way from the start. The other
 * two were not: both issued a bare synchronous Redis call, so a blip at the wrong moment threw out
 * of {@code BookingOrchestrationService#createBooking} — {@code complete()} turning a booking that
 * had already been persisted, paid for and published into a 500 for the customer, and
 * {@code release()} replacing the very exception it was cleaning up after, since every caller runs
 * it in a catch block immediately before rethrowing.
 */
@ExtendWith(MockitoExtension.class)
class RedissonIdempotencyStoreFailureTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> bucket;

    private RedissonIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new RedissonIdempotencyStore(redissonClient);
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
    }

    @Test
    void completeDoesNotFailTheBookingThatAlreadySucceeded() {
        RFuture<Void> refused = failed();
        when(bucket.setAsync(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(refused);

        assertThatCode(() -> store.complete("key-1", "booking-1")).doesNotThrowAnyException();
    }

    /**
     * And the write is not simply lost: the local cache is still populated, so this pod answers
     * its own client's retry from memory rather than re-running the saga.
     */
    @Test
    void completeStillRemembersTheResultLocallyWhenRedisRefusedIt() {
        RFuture<Void> refused = failed();
        when(bucket.setAsync(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(refused);

        store.complete("key-2", "booking-2");

        assertThat(store.claim("key-2"))
                .isInstanceOfSatisfying(IdempotencyClaim.Completed.class,
                        completed -> assertThat(completed.bookingId()).isEqualTo("booking-2"));
    }

    @Test
    void releaseDoesNotReplaceTheExceptionItIsCleaningUpAfter() {
        RFuture<Boolean> refused = failedBoolean();
        when(bucket.deleteAsync()).thenReturn(refused);

        assertThatCode(() -> store.release("key-3")).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static RFuture<Void> failed() {
        RFuture<Void> future = mock(RFuture.class);
        when(future.toCompletableFuture())
                .thenReturn(CompletableFuture.failedFuture(new RedisException("Redis is down")));
        return future;
    }

    @SuppressWarnings("unchecked")
    private static RFuture<Boolean> failedBoolean() {
        RFuture<Boolean> future = mock(RFuture.class);
        when(future.toCompletableFuture())
                .thenReturn(CompletableFuture.failedFuture(new RedisException("Redis is down")));
        return future;
    }
}
