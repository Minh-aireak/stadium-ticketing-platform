package com.aireak.inventory.adapter.in.web;

import com.aireak.inventory.adapter.in.web.dto.ReserveSeatsRequest;
import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound REST adapter for seat inventory.
 * reserve/release/confirm are called synchronously by booking-service during saga execution;
 * the seat-map GET below is called by the frontend to render seat selection.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Validated
public class SeatInventoryController {

    private final ReserveSeatsUseCase reserveSeatsUseCase;
    private final ReleaseSeatsUseCase releaseSeatsUseCase;
    private final ConfirmSeatsUseCase confirmSeatsUseCase;
    private final GetSeatMapUseCase getSeatMapUseCase;

    /**
     * POST /api/v1/inventory/{showtimeId}/reserve — places a TTL hold (see SeatHoldPort), no DB write.
     * Returns the authoritative total price computed from each seat's tier — booking-service uses
     * this to charge, never a client-supplied amount.
     */
    @PostMapping("/{showtimeId}/reserve")
    public ResponseEntity<ReserveSeatsResponse> reserve(@PathVariable("showtimeId") String showtimeId,
                                        @Valid @RequestBody ReserveSeatsRequest request) {
        BigDecimal totalPrice = reserveSeatsUseCase.execute(
                new ReserveSeatsCommand(showtimeId, request.bookingId(), request.seatCodes()));
        return ResponseEntity.ok(new ReserveSeatsResponse(totalPrice));
    }

    /** DELETE /api/v1/inventory/{showtimeId}/reserve/{bookingId} */
    @DeleteMapping("/{showtimeId}/reserve/{bookingId}")
    public ResponseEntity<Void> release(@PathVariable String showtimeId,
                                        @PathVariable String bookingId,
                                        @RequestParam @NotEmpty java.util.List<String> seatCodes) {
        releaseSeatsUseCase.execute(
                new ReleaseSeatsCommand(showtimeId, bookingId, seatCodes));
        return ResponseEntity.ok().build();
    }

    /** POST /api/v1/inventory/{showtimeId}/confirm — finalizes the sale (payment succeeded). */
    @PostMapping("/{showtimeId}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable String showtimeId,
                                        @Valid @RequestBody ReserveSeatsRequest request) {
        confirmSeatsUseCase.execute(
                new ConfirmSeatsCommand(showtimeId, request.bookingId(), request.seatCodes()));
        return ResponseEntity.ok().build();
    }

    /** GET /api/v1/inventory/{showtimeId}/seats — seat map for seat selection, incl. live Redis holds. */
    @GetMapping("/{showtimeId}/seats")
    public ResponseEntity<SeatMapResponse> getSeatMap(@PathVariable String showtimeId) {
        return getSeatMapUseCase.getSeatMap(showtimeId)
                .map(result -> ResponseEntity.ok(toResponse(result)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private SeatMapResponse toResponse(GetSeatMapUseCase.SeatMapResult result) {
        List<SeatResponse> seats = result.seats().stream().map(this::toSeatResponse).toList();
        return new SeatMapResponse(result.showtimeId(), seats);
    }

    private SeatResponse toSeatResponse(GetSeatMapUseCase.SeatSummary seat) {
        String code = seat.seatCode();
        String row = code.substring(0, 1);
        int number = Integer.parseInt(code.substring(1));
        return new SeatResponse(code, row, number, seat.status().toLowerCase(), seat.tier().toLowerCase(),
                seat.price());
    }

    record SeatResponse(String code, String row, int number, String status, String tier, BigDecimal price) {}
    record SeatMapResponse(String showtimeId, List<SeatResponse> seats) {}
    record ReserveSeatsResponse(BigDecimal totalPrice) {}
}
