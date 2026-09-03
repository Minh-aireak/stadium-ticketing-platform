package com.aireak.inventory.adapter.in.web;

import com.aireak.common.exception.ForbiddenException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.inventory.adapter.in.web.dto.HoldSeatsRequest;
import com.aireak.inventory.adapter.in.web.dto.ReserveSeatsRequest;
import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.in.GetSeatingLayoutUseCase;
import com.aireak.inventory.application.port.in.HoldSeatsUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.UnholdSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;
import com.aireak.inventory.application.port.in.command.HoldSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import com.aireak.inventory.application.port.in.command.UnholdSeatsCommand;
import com.aireak.inventory.domain.exception.ShowtimeCatalogUnavailableException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Inbound REST adapter for seat inventory.
 * reserve/release/confirm are called synchronously by booking-service during saga execution;
 * hold/unhold/seat-map GET below are called directly by the frontend for seat selection.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Validated
public class SeatInventoryController {

    private static final String RETRY_AFTER_SECONDS = "1";

    private final ReserveSeatsUseCase reserveSeatsUseCase;
    private final ReleaseSeatsUseCase releaseSeatsUseCase;
    private final ConfirmSeatsUseCase confirmSeatsUseCase;
    private final GetSeatMapUseCase getSeatMapUseCase;
    private final GetSeatingLayoutUseCase getSeatingLayoutUseCase;
    private final HoldSeatsUseCase holdSeatsUseCase;
    private final UnholdSeatsUseCase unholdSeatsUseCase;

    /**
     * The currency this deployment prices seats in, returned alongside every price below.
     *
     * <p>Seat rows carry a bare {@code NUMERIC} price with no currency of their own, so until now
     * the only party naming a currency for a booking was the client — booking-service took it
     * straight from the request body and carried it all the way to the Stripe charge, where it
     * decides whether the amount is multiplied by 100 (see payment-service's
     * {@code StripeGatewayAdapter#toSmallestUnit}, which treats VND/JPY/KRW as zero-decimal). A
     * client choosing that was a client choosing the size of its own charge. Pairing the currency
     * with the price at the one place that computes the price removes the client from the question
     * entirely, which no allowlist on the client's value could do.
     */
    @Value("${inventory.pricing.currency}")
    private String pricingCurrency;

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
        return ResponseEntity.ok(new ReserveSeatsResponse(totalPrice, pricingCurrency));
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
        return ResponseEntity.ok(new HoldSeatsResponse(totalPrice, pricingCurrency));
    }

    /** DELETE /api/v1/inventory/{showtimeId}/hold — releases the caller's own standalone pre-booking hold. */
    @DeleteMapping("/{showtimeId}/hold")
    public ResponseEntity<Void> unhold(@PathVariable String showtimeId,
                                        @RequestParam @NotEmpty List<String> seatCodes) {
        unholdSeatsUseCase.execute(new UnholdSeatsCommand(showtimeId, currentUserId(), seatCodes));
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /api/v1/inventory/{showtimeId}/reserve/{bookingId} — called with either an
     * internal-service token (trusted unconditionally, e.g. the PAYMENT_FAILED-compensation
     * path off a Kafka listener thread) or the original customer's own token (the
     * createBooking-compensation path, still inside their HTTP request — see
     * {@code TicketInventoryRestAdapter#authorizationToken}). For the latter, the bookingId path
     * variable alone isn't proof of ownership, so the service layer verifies the caller actually
     * owns the reservation it's asking to release (see {@code ReleaseSeatsCommand}).
     */
    @DeleteMapping("/{showtimeId}/reserve/{bookingId}")
    public ResponseEntity<Void> release(@PathVariable String showtimeId,
                                        @PathVariable String bookingId,
                                        @RequestParam @NotEmpty java.util.List<String> seatCodes) {
        AuthenticatedUser caller = currentUser();
        ReleaseSeatsCommand command = caller.isInternalService()
                ? new ReleaseSeatsCommand(showtimeId, bookingId, seatCodes)
                : new ReleaseSeatsCommand(showtimeId, bookingId, seatCodes, caller.userId());
        releaseSeatsUseCase.execute(command);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/v1/inventory/{showtimeId}/confirm — finalizes the sale (payment succeeded).
     * Internal-service token only: {@code TicketInventoryRestAdapter#confirmReservation} never
     * forwards a real customer token, so a request bearing one here cannot be a genuine
     * post-payment confirmation — see {@link #requireInternalService}.
     */
    @PostMapping("/{showtimeId}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable String showtimeId,
                                        @Valid @RequestBody ReserveSeatsRequest request) {
        requireInternalService();
        confirmSeatsUseCase.execute(
                new ConfirmSeatsCommand(showtimeId, request.bookingId(), request.seatCodes()));
        return ResponseEntity.ok().build();
    }

    /**
     * GET /api/v1/inventory/{showtimeId}/seats — seat map for seat selection, incl. live Redis
     * holds. Passes the authenticated caller so each HELD seat can say whether the hold is
     * theirs ({@code heldByYou}); it does not otherwise scope what is returned.
     */
    @GetMapping("/{showtimeId}/seats")
    public ResponseEntity<SeatMapResponse> getSeatMap(@PathVariable String showtimeId) {
        return getSeatMapUseCase.getSeatMap(showtimeId, currentUserId())
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
                seat.price(), seat.heldByCaller());
    }

    /**
     * GET /api/v1/inventory/{showtimeId}/layout — static seating topology (sections/blocks) for
     * the frontend's Section/Block picker. Separate from {@link #getSeatMap}, which reflects live
     * hold/sold state: this is best-effort/cacheable topology, so the frontend treats a 404 here
     * (or the endpoint being unreachable) as "no Section/Block picker available" and falls back to
     * the plain seat map.
     */
    @GetMapping("/{showtimeId}/layout")
    public ResponseEntity<LayoutResponse> getLayout(@PathVariable String showtimeId) {
        return getSeatingLayoutUseCase.getLayout(showtimeId)
                .map(result -> ResponseEntity.ok(toLayoutResponse(result)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private LayoutResponse toLayoutResponse(GetSeatingLayoutUseCase.LayoutResult result) {
        List<SectionResponse> sections = result.sections().stream().map(this::toSectionResponse).toList();
        return new LayoutResponse(result.showtimeId(), sections);
    }

    private SectionResponse toSectionResponse(GetSeatingLayoutUseCase.SectionSummary section) {
        String tierSlug = section.tier().toLowerCase();
        List<BlockResponse> blocks = section.blocks().stream()
                .map(block -> toBlockResponse(tierSlug, block))
                .toList();
        return new SectionResponse("sec-" + tierSlug, section.tier(), blocks);
    }

    private BlockResponse toBlockResponse(String tierSlug, GetSeatingLayoutUseCase.BlockSummary block) {
        String rowSlug = block.row().toLowerCase();
        return new BlockResponse("blk-" + tierSlug + "-" + rowSlug, block.row(), block.seatCodes());
    }

    /**
     * match-catalog-service unreachable → 503 with a Retry-After, rather than the 422 this used to
     * share with a genuinely closed booking window (see
     * {@link ShowtimeCatalogUnavailableException} for why that was three bugs, not one).
     *
     * <p>Controller-local rather than another {@code @ControllerAdvice}: a handler on the
     * controller beats every advice regardless of order, which is exactly the failure mode
     * {@code InventoryOverloadExceptionHandler}'s javadoc exists to document. It also belongs
     * here rather than in that class, which is about load-shedding — this is a dependency being
     * down, and putting it there would make that class's name describe half of what it does.
     *
     * <p>The detail is a constant: the exception's own message names the showtimeId, and this
     * response reaches a browser toast. Being a constant is also what lets the frontend say it in
     * Vietnamese — errors.ts keys that sentence off the {@code type} URI below rather than off
     * this English, which only holds while one type means exactly one failure. The adapter has
     * already logged the cause with a stack trace, so this does not repeat it.
     */
    @ExceptionHandler(ShowtimeCatalogUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleCatalogUnavailable(ShowtimeCatalogUnavailableException ex) {
        log.warn("Rejecting a seat request because match-catalog-service is unavailable: {}",
                ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Ticket availability cannot be verified right now");
        problem.setType(URI.create("https://aireak.com/errors/catalog-unavailable"));
        problem.setTitle("Service Unavailable");
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }

    // JwtAuthenticationFilter runs for every non-excluded path (no exclusion here), so this is
    // always populated by the time controller code executes — same pattern as booking-service's
    // BookingController.
    private AuthenticatedUser currentUser() {
        return AuthenticatedUserContext.get()
                .orElseThrow(() -> new IllegalStateException("JwtAuthenticationFilter did not run for this request"));
    }

    private String currentUserId() {
        return currentUser().userId();
    }

    /**
     * No Spring Security in this service (see JwtAuthenticationFilter's javadoc) — same
     * controller-level pattern as match-catalog-service's {@code MatchController#requireAdminRole}.
     */
    private void requireInternalService() {
        if (!currentUser().isInternalService()) {
            throw new ForbiddenException("This operation is restricted to internal service calls");
        }
    }

    /**
     * {@code heldByYou} distinguishes the caller's own hold from anyone else's on a {@code held}
     * seat, so the frontend can restore a selection the customer already paid a hold for instead
     * of rendering it as taken (see the storefront's SeatSelectionPage).
     */
    record SeatResponse(String code, String row, int number, String status, String tier, BigDecimal price,
                        boolean heldByYou) {}
    record SeatMapResponse(String showtimeId, List<SeatResponse> seats) {}
    record ReserveSeatsResponse(BigDecimal totalPrice, String currency) {}
    record HoldSeatsResponse(BigDecimal totalPrice, String currency) {}
    record BlockResponse(String id, String name, List<String> seatCodes) {}
    record SectionResponse(String id, String name, List<BlockResponse> blocks) {}
    record LayoutResponse(String showtimeId, List<SectionResponse> sections) {}
}
