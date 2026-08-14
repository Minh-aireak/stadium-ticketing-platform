package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.GenerateSeatMapUseCase;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.application.port.out.ShowtimeCatalogPort;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatMapLayout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Application service: generates the seat map for a newly-added showtime.
 *
 * <p>Idempotent on {@code showtimeId} — a redelivered {@code ShowtimeAddedEvent} (at-least-once
 * Kafka delivery, consumer restart, etc.) must not regenerate/duplicate the seat map, so this
 * checks {@link SeatInventoryRepository#existsByShowtimeId} first rather than relying on a DB
 * constraint violation to signal "already done".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatMapGenerationService implements GenerateSeatMapUseCase {

    private final SeatInventoryRepository seatInventoryRepository;
    private final ShowtimeCatalogPort showtimeCatalogPort;

    @Override
    @Transactional
    public void generate(String showtimeId, String stadiumId, Instant startTime,
                          int expectedTotalSeats, BigDecimal basePrice) {
        // Unconditional, even on the idempotent-replay path below: this pod's local cache may be
        // cold (e.g. after a restart) and this is the only event carrying the showtime's start
        // time, so a replay is the only chance to repopulate it.
        showtimeCatalogPort.rememberStartTime(showtimeId, startTime);

        if (seatInventoryRepository.existsByShowtimeId(showtimeId)) {
            log.debug("Seat map already generated for showtime={}, skipping (idempotent replay)", showtimeId);
            return;
        }

        List<Seat> seats = SeatMapLayout.generate(stadiumId, basePrice);
        if (seats.size() != expectedTotalSeats) {
            throw new IllegalArgumentException("Stadium capacity mismatch for " + stadiumId
                    + ": event=" + expectedTotalSeats + ", layout=" + seats.size());
        }
        SeatInventory inventory = SeatInventory.create(showtimeId, seats);
        seatInventoryRepository.save(inventory);

        log.info("Seat map generated: showtime={}, stadium={}, seats={}", showtimeId, stadiumId, seats.size());
    }
}
