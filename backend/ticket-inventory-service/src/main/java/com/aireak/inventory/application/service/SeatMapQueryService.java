package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Application service: reads the seat map for a showtime.
 *
 * <p>A seat AVAILABLE in Postgres may still be actively held by another booking's in-flight
 * reservation — holds live only in Redis (see {@link SeatHoldPort}), never written to the
 * {@code seats} table (see {@code SeatInventoryService} javadoc), so every AVAILABLE seat must
 * be cross-checked against Redis here to tell "actually free" from "someone else is mid-checkout".
 */
@Service
@RequiredArgsConstructor
public class SeatMapQueryService implements GetSeatMapUseCase {

    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatHoldPort seatHoldPort;

    @Override
    public Optional<SeatMapResult> getSeatMap(String showtimeId) {
        return seatInventoryRepository.findByShowtimeId(showtimeId)
                .map(inventory -> toResult(showtimeId, inventory));
    }

    private SeatMapResult toResult(String showtimeId, SeatInventory inventory) {
        var seats = inventory.getSeats().stream()
                .map(seat -> new SeatSummary(
                        seat.getSeatCode().value(),
                        statusOf(showtimeId, seat),
                        seat.getTier().name(),
                        seat.getPrice()))
                .toList();
        return new SeatMapResult(showtimeId, seats);
    }

    private String statusOf(String showtimeId, Seat seat) {
        if (seat.getStatus() == SeatStatus.SOLD) return "SOLD";
        if (seatHoldPort.isHeld(showtimeId, seat.getSeatCode())) return "HELD";
        return "AVAILABLE";
    }
}
