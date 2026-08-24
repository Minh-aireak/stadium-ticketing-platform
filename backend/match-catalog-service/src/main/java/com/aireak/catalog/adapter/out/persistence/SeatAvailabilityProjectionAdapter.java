package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.application.port.out.SeatAvailabilityProjectionPort;
import com.aireak.catalog.application.port.out.ShowtimeSeatCountPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Postgres half of the sold-seat projection: the idempotent decrement of
 * {@code showtimes.available_seats}, and the read that lets the Redis counter be re-derived from it.
 *
 * <p>Strictly the durable side now. It used to also write the count through to Redis, by reading
 * the freshly decremented total back and overwriting the key with it — a read-modify-write across
 * two systems with no atomicity, so two overlapping projections could each read before either
 * wrote and the slower one would silently reinstate seats already sold. Redis is decremented
 * atomically by {@code RedisSeatAvailabilityCounter} instead, and orchestrated alongside this
 * method by {@code SoldSeatsProjectionService}.
 *
 * <p>The idempotency check and the decrement share one transaction, which is what makes replayed
 * Kafka deliveries safe: a concurrent duplicate cannot slip between the {@code existsById} and the
 * insert, because the second one blocks on the first's uncommitted primary key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SeatAvailabilityProjectionAdapter implements SeatAvailabilityProjectionPort, ShowtimeSeatCountPort {

    private final MatchJpaRepository matchJpaRepository;
    private final ProcessedInventoryEventJpaRepository processedEventRepository;

    @Override
    @Transactional
    public boolean decrementAvailableSeats(String eventId, String showtimeId, int soldSeatCount) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Sold-seat event already recorded, skipping the Postgres decrement: eventId={}, showtime={}",
                    eventId, showtimeId);
            return false;
        }
        int updated = matchJpaRepository.decrementAvailableSeats(showtimeId, soldSeatCount);
        if (updated == 0) {
            throw new IllegalStateException("Showtime not found for sold-seat event: " + showtimeId);
        }
        processedEventRepository.save(new ProcessedInventoryEventJpaEntity(eventId, Instant.now()));
        return true;
    }

    /**
     * Read on the recovery path only, and deliberately uncached — its whole purpose is to answer
     * "what does the source of truth actually say" when the Redis counter is suspect.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Integer> findAvailableSeats(String showtimeId) {
        return matchJpaRepository.findAvailableSeats(showtimeId);
    }
}
