package com.aireak.inventory.adapter.in.web;

import com.aireak.inventory.adapter.in.web.dto.ReserveSeatsRequest;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Inbound REST adapter for seat inventory.
 * Called synchronously by booking-service during saga execution.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class SeatInventoryController {

    private final ReserveSeatsUseCase reserveSeatsUseCase;
    private final ReleaseSeatsUseCase releaseSeatsUseCase;

    /** POST /api/v1/inventory/{showtimeId}/reserve */
    @PostMapping("/{showtimeId}/reserve")
    public ResponseEntity<Void> reserve(@PathVariable String showtimeId,
                                        @Valid @RequestBody ReserveSeatsRequest request) {
        reserveSeatsUseCase.execute(
                new ReserveSeatsCommand(showtimeId, request.bookingId(), request.seatCodes()));
        return ResponseEntity.ok().build();
    }

    /** DELETE /api/v1/inventory/{showtimeId}/reserve/{bookingId} */
    @DeleteMapping("/{showtimeId}/reserve/{bookingId}")
    public ResponseEntity<Void> release(@PathVariable String showtimeId,
                                        @PathVariable String bookingId,
                                        @RequestParam java.util.List<String> seatCodes) {
        releaseSeatsUseCase.execute(
                new ReleaseSeatsCommand(showtimeId, bookingId, seatCodes));
        return ResponseEntity.ok().build();
    }
}
