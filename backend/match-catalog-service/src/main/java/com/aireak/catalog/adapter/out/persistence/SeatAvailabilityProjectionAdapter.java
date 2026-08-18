package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.application.port.out.LiveSeatAvailabilityPort;
import com.aireak.catalog.application.port.out.SeatAvailabilityProjectionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
class SeatAvailabilityProjectionAdapter implements SeatAvailabilityProjectionPort {

    private final MatchJpaRepository matchJpaRepository;
    private final ProcessedInventoryEventJpaRepository processedEventRepository;
    private final LiveSeatAvailabilityPort liveSeatAvailabilityPort;

    @Override
    @Transactional
    public boolean decrementAvailableSeats(String eventId, String showtimeId, int soldSeatCount) {
        if (processedEventRepository.existsById(eventId)) {
            return false;
        }
        int updated = matchJpaRepository.decrementAvailableSeats(showtimeId, soldSeatCount);
        if (updated == 0) {
            throw new IllegalStateException("Showtime not found for sold-seat event: " + showtimeId);
        }
        processedEventRepository.save(new ProcessedInventoryEventJpaEntity(eventId, Instant.now()));
        publishLiveSeatCount(showtimeId);
        return true;
    }

    // Best-effort write-through so listMatches/getMatch can serve the new count from Redis
    // without waiting on a cache TTL — never lets a Redis hiccup fail the projection itself,
    // since Postgres (already updated above) stays the durable source of truth either way.
    private void publishLiveSeatCount(String showtimeId) {
        try {
            matchJpaRepository.findAvailableSeats(showtimeId)
                    .ifPresent(seats -> liveSeatAvailabilityPort.publish(showtimeId, seats));
        } catch (Exception e) {
            log.warn("Failed to publish live seat count to Redis, DB stays authoritative: showtimeId={}, error={}",
                    showtimeId, e.getMessage());
        }
    }
}
