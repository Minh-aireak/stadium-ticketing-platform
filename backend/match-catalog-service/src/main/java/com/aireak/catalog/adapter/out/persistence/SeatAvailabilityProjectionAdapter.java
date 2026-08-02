package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.application.port.out.SeatAvailabilityProjectionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
class SeatAvailabilityProjectionAdapter implements SeatAvailabilityProjectionPort {

    private final MatchJpaRepository matchJpaRepository;
    private final ProcessedInventoryEventJpaRepository processedEventRepository;

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
        return true;
    }
}
