package com.aireak.inventory.adapter.in.web;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.inventory.adapter.in.web.dto.HoldSeatsRequest;
import com.aireak.inventory.adapter.in.web.dto.ReserveSeatsRequest;
import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.in.HoldSeatsUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.UnholdSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;
import com.aireak.inventory.application.port.in.command.HoldSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import com.aireak.inventory.application.port.in.command.UnholdSeatsCommand;
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
 * hold/unhold/seat-map GET below are called directly by the frontend for seat selection.
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
    private final HoldSeatsUseCase holdSeatsUseCase;
    private final UnholdSeatsUseCase unholdSeatsUseCase;

    /**
     * POST /api/v1/inventory/{showtimeId}/reserve — places a TTL hold (see SeatHoldPort), no DB write.
     * Returns the authoritative total price computed from each seat's tier — booking-service uses
     * this to charge, never a client-supplied amount. Confirms the caller's own standalone
     * pre-booking hold (see {@link #hold}) over into this booking instead of re-acquiring it —
     * this endpoint is only ever called with the original customer's own forwarded JWT (never an
     * internal service token), so {@code currentUserId()} here is that customer's id.
     */
    @PostMapping("/{showtimeId}/reserve")
    public ResponseEntity<ReserveSeatsResponse> reserve(@PathVariable("showtimeId") String showtimeId,
                                        @Valid @RequestBody ReserveSeatsRequest request) {
        BigDecimal totalPrice = reserveSeatsUseCase.execute(
                new ReserveSeatsCommand(showtimeId, request.bookingId(), currentUserId(), request.seatCodes()));
        return ResponseEntity.ok(new ReserveSeatsResponse(totalPrice));
    }

    /**
     * POST /api/v1/inventory/{showtimeId}/hold — standalone pre-booking hold, called directly by
     * the frontend as soon as a customer selects a seat (well before a booking exists). Same TTL
     * hold mechanism as {@link #reserve}, owned by the authenticated customer instead of a booking.
     */
    @PostMapping("/{showtimeId}/hold")
    public ResponseEntity<HoldSeatsResponse> hold(@PathVariable String showtimeId,
                                        @Valid @RequestBody HoldSeatsRequest request) {
        BigDecimal totalPrice = holdSeatsUseCase.execute(
                new HoldSeatsCommand(showtimeId, currentUserId(), request.seatCodes()));
        return ResponseEntity.ok(new HoldSeatsResponse(totalPrice));
    }

    /** DELETE /api/v1/inventory/{showtimeId}/hold — releases the caller's own standalone pre-booking hold. */
    @DeleteMapping("/{showtimeId}/hold")
    public ResponseEntity<Void> unhold(@PathVariable String showtimeId,
                                        @RequestParam @NotEmpty List<String> seatCodes) {
        unholdSeatsUseCase.execute(new UnholdSeatsCommand(showtimeId, currentUserId(), seatCodes));
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

    // JwtAuthenticationFilter runs for every non-excluded path (no exclusion here), so this is
    // always populated by the time controller code executes — same pattern as booking-service's
    // BookingController.
    private String currentUserId() {
        return AuthenticatedUserContext.get()
                .map(AuthenticatedUser::userId)
                .orElseThrow(() -> new IllegalStateException("JwtAuthenticationFilter did not run for this request"));
    }

    record SeatResponse(String code, String row, int number, String status, String tier, BigDecimal price) {}
    record SeatMapResponse(String showtimeId, List<SeatResponse> seats) {}
    record ReserveSeatsResponse(BigDecimal totalPrice) {}
    record HoldSeatsResponse(BigDecimal totalPrice) {}
}
