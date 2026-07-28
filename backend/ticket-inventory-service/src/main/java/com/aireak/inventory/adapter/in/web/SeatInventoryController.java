package com.aireak.inventory.adapter.in.web;

import com.aireak.inventory.adapter.in.web.dto.ReserveSeatsRequest;
import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
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

/**
 * Inbound REST adapter for seat inventory.
 * Called synchronously by booking-service during saga execution.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Validated
public class SeatInventoryController {

    private final ReserveSeatsUseCase reserveSeatsUseCase;
    private final ReleaseSeatsUseCase releaseSeatsUseCase;
    private final ConfirmSeatsUseCase confirmSeatsUseCase;

    /** POST /api/v1/inventory/{showtimeId}/reserve — places a TTL hold (see SeatHoldPort), no DB write. */
    @PostMapping("/{showtimeId}/reserve")
    public ResponseEntity<Void> reserve(@PathVariable("showtimeId") String showtimeId,
                                        @Valid @RequestBody ReserveSeatsRequest request) {
        reserveSeatsUseCase.execute(
                new ReserveSeatsCommand(showtimeId, request.bookingId(), request.seatCodes()));
        return ResponseEntity.ok().build();
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
}
