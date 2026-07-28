package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.GenerateSeatMapUseCase;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatMapLayout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    @Override
    @Transactional
    public void generate(String showtimeId, int totalSeats, BigDecimal basePrice) {
        if (seatInventoryRepository.existsByShowtimeId(showtimeId)) {
            log.debug("Seat map already generated for showtime={}, skipping (idempotent replay)", showtimeId);
            return;
        }

        List<Seat> seats = SeatMapLayout.generate(totalSeats, basePrice);
        SeatInventory inventory = SeatInventory.create(showtimeId, seats);
        seatInventoryRepository.save(inventory);

        log.info("Seat map generated: showtime={}, totalSeats={}, seats={}", showtimeId, totalSeats, seats.size());
    }
}
