package com.aireak.booking.adapter.in.web;

import com.aireak.booking.application.port.in.CreateBookingUseCase;
import com.aireak.booking.application.port.in.GetBookingUseCase;
import com.aireak.booking.application.port.in.ListBookingsUseCase;
import com.aireak.booking.application.port.in.dto.BookingCreationResult;
import com.aireak.booking.application.service.DuplicateRequestInProgressException;
import com.aireak.booking.domain.model.Booking;
import com.aireak.common.exception.IdentityMismatchException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CreateBookingUseCase createBookingUseCase;
    private final GetBookingUseCase getBookingUseCase;
    private final ListBookingsUseCase listBookingsUseCase;

    @PostMapping
    public ResponseEntity<CreateBookingResponse> createBooking(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        requireMatchingIdentity(request.customerId());
        BookingCreationResult result = createBookingUseCase.createBooking(
                idempotencyKey,
                request.customerId(),
                request.showtimeId(),
                request.seatCodes(),
                request.amount(),
                request.currency()
        );
        // 201 = the booking resource now exists; it does NOT mean payment is confirmed —
        // callers must read `status`. PENDING_PAYMENT is the expected status even on a
        // fully successful call, since payment confirmation is always asynchronous
        // (PaymentResultConsumer) except for the rare ambiguous-but-reconciled case.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateBookingResponse(result.bookingId(), result.status().name()));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingStatusResponse> getBooking(@PathVariable("bookingId") String bookingId) {
        return getBookingUseCase.getBooking(bookingId)
                .map(b -> ResponseEntity.ok(new BookingStatusResponse(b.getBookingId(), b.getStatus().name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/bookings?page=&size= — "my tickets": always scoped to the JWT-authenticated
     * caller, never to a client-supplied customerId (same reasoning as {@link #requireMatchingIdentity}
     * below — otherwise any authenticated user could list anyone else's bookings).
     */
    @GetMapping
    public ResponseEntity<BookingListResponse> listMine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);

        String customerId = currentUser().userId();
        ListBookingsUseCase.BookingPage result = listBookingsUseCase.listByCustomer(customerId, safePage, safeSize);
        return ResponseEntity.ok(toListResponse(result));
    }

    private BookingListResponse toListResponse(ListBookingsUseCase.BookingPage result) {
        return new BookingListResponse(
                result.items().stream().map(this::toSummary).toList(),
                result.totalElements(), result.page(), result.size());
    }

    private BookingSummaryResponse toSummary(Booking booking) {
        return new BookingSummaryResponse(
                booking.getBookingId(), booking.getShowtimeId(), booking.getSeatSelection().seatCodes(),
                booking.getAmount().amount(), booking.getAmount().currency(),
                booking.getStatus().name(), booking.getCreatedAt());
    }

    private AuthenticatedUser currentUser() {
        return AuthenticatedUserContext.get()
                .orElseThrow(() -> new IllegalStateException("JwtAuthenticationFilter did not run for this request"));
    }

    // customerId is client-supplied in the body — without this check, any authenticated caller
    // could create bookings billed to someone else's account.
    private void requireMatchingIdentity(String customerId) {
        if (!currentUser().userId().equals(customerId)) {
            throw new IdentityMismatchException(
                    "customerId does not match the authenticated caller");
        }
    }

    // amount: display/log placeholder only — the saga overwrites it with the price
    // ticket-inventory-service computes server-side from each seat's tier before any
    // charge-relevant step runs (see BookingOrchestrationService#createBooking, Step 2b).
    // NEVER used to compute the actual charge.
    public record CreateBookingRequest(
            @NotBlank String customerId,
            @NotBlank String showtimeId,
            @NotEmpty List<String> seatCodes,
            @Positive BigDecimal amount,
            @NotBlank String currency
    ) {}

    public record CreateBookingResponse(String bookingId, String status) {}

    public record BookingStatusResponse(String bookingId, String status) {}

    public record BookingSummaryResponse(String bookingId, String showtimeId, List<String> seatCodes,
                                        BigDecimal amount, String currency, String status, Instant createdAt) {}

    public record BookingListResponse(List<BookingSummaryResponse> items, long totalElements, int page, int size) {}

    // Handles duplicate in-flight requests with 409 Conflict
    @ExceptionHandler(DuplicateRequestInProgressException.class)
    public ProblemDetail handleDuplicateRequestInProgress(DuplicateRequestInProgressException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://aireak.com/errors/duplicate-request-in-progress"));
        problem.setTitle("Duplicate Request In Progress");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
