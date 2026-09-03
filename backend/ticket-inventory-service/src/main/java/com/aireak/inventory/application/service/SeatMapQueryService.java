package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Application service: reads the seat map for a showtime.
 *
 * <p>A seat AVAILABLE in Postgres may still be actively held by another booking's in-flight
 * reservation — holds live only in Redis (see {@link SeatHoldPort}), never written to the
 * {@code seats} table (see {@code SeatInventoryService} javadoc), so every AVAILABLE seat must
 * be cross-checked against Redis here to tell "actually free" from "someone is mid-checkout".
 *
 * <p>Which someone matters too: the hold's owner comes back in the same round trip, so a seat
 * held by the caller themselves is marked {@code heldByCaller} rather than being reported as
 * indistinguishable from another shopper's hold.
 */
@Service
@RequiredArgsConstructor
public class SeatMapQueryService implements GetSeatMapUseCase {

    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatHoldPort seatHoldPort;

    @Override
    public Optional<SeatMapResult> getSeatMap(String showtimeId, String callerId) {
        return seatInventoryRepository.findByShowtimeId(showtimeId)
                .map(inventory -> toResult(showtimeId, inventory, callerId));
    }

    private SeatMapResult toResult(String showtimeId, SeatInventory inventory, String callerId) {
        // One batched Redis round trip for the whole seat map instead of one isHeld() call per
        // seat — only AVAILABLE-in-Postgres seats can possibly be held (SOLD short-circuits
        // below), but querying just those still means a variable-size key set per showtime, so
        // this queries every seat's key together rather than trying to pre-filter client-side.
        List<SeatCode> seatCodes = inventory.getSeats().stream().map(Seat::getSeatCode).toList();
        Map<SeatCode, String> holdOwners = seatHoldPort.findHoldOwners(showtimeId, seatCodes);

        var seats = inventory.getSeats().stream()
                .map(seat -> new SeatSummary(
                        seat.getSeatCode().value(),
                        statusOf(seat, holdOwners),
                        seat.getTier().name(),
                        seat.getPrice(),
                        isHeldByCaller(seat, holdOwners, callerId)))
                .toList();
        return new SeatMapResult(showtimeId, seats);
    }

    private String statusOf(Seat seat, Map<SeatCode, String> holdOwners) {
        if (seat.getStatus() == SeatStatus.SOLD) return "SOLD";
        if (holdOwners.containsKey(seat.getSeatCode())) return "HELD";
        return "AVAILABLE";
    }

    // Deliberately false for a SOLD seat even if a stale hold entry still names the caller: the
    // sale is the final word, and a seat the caller can no longer buy must not come back looking
    // reselectable.
    private boolean isHeldByCaller(Seat seat, Map<SeatCode, String> holdOwners, String callerId) {
        if (seat.getStatus() == SeatStatus.SOLD || callerId == null) return false;
        return callerId.equals(holdOwners.get(seat.getSeatCode()));
    }
}
