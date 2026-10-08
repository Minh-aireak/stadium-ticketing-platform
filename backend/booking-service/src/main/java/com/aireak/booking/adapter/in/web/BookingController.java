package com.aireak.booking.adapter.in.web;

import com.aireak.booking.application.port.in.CancelBookingCommand;
import com.aireak.booking.application.port.in.CancelBookingUseCase;
import com.aireak.booking.application.port.in.CreateBookingUseCase;
import com.aireak.booking.application.port.in.GetBookingUseCase;
import com.aireak.booking.application.port.in.ListBookingsUseCase;
import com.aireak.booking.application.port.in.dto.BookingCreationResult;
import com.aireak.booking.application.port.out.OutboundServiceUnavailableException;
import com.aireak.booking.application.service.CancellationInProgressException;
import com.aireak.booking.application.service.DuplicateRequestInProgressException;
import com.aireak.booking.domain.exception.CancellationConflictException;
import com.aireak.booking.domain.exception.CancellationWindowClosedException;
import com.aireak.booking.domain.exception.TicketIssuanceInProgressException;
import com.aireak.booking.domain.model.Booking;
import com.aireak.common.exception.IdentityMismatchException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpServerErrorException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    // Kept in step with ticket-inventory-service's SeatCode value object, which is what
    // ultimately parses these; a code this rejects is one the saga would fail on later.
    private static final String SEAT_CODE_PATTERN = "^[A-Z]\\d{1,3}$";

    private static final String IDEMPOTENCY_KEY_SEPARATOR = ":";

    // bookings.idempotency_key is VARCHAR(255) and now stores the caller's account id (a 36-char
    // UUID) plus a separator ahead of the client's key. Rejecting an over-long key here answers
    // 400 saying so, instead of letting it become a "value too long" at commit that
    // GlobalExceptionHandler can only report as 409 "The request conflicts with existing data".
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private static final String RETRY_AFTER_SECONDS = "1";

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CreateBookingUseCase createBookingUseCase;
    private final GetBookingUseCase getBookingUseCase;
    private final ListBookingsUseCase listBookingsUseCase;
    private final CancelBookingUseCase cancelBookingUseCase;

    @PostMapping
    public ResponseEntity<CreateBookingResponse> createBooking(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        requireMatchingIdentity(request.customerId());
        // currentUser().email() is safe to denormalize onto the booking here: requireMatchingIdentity
        // above already proved this request is the account's own, and the email comes straight off
        // the JWT identity-service issued for it (see JwtTokenGeneratorAdapter) — not client input,
        // and no extra call to identity-service needed (see CreateBookingUseCase javadoc).
        BookingCreationResult result = createBookingUseCase.createBooking(
                scopedIdempotencyKey(idempotencyKey),
                request.customerId(),
                currentUser().email(),
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

    /**
     * GET /api/v1/bookings/{bookingId} — the booking's current status and, critically, the
     * <strong>authoritative amount</strong> the saga computed server-side from each seat's tier
     * (see {@code BookingOrchestrationService#createBooking}, Step 2b).
     *
     * <p>payment-service calls this endpoint to authorize a payment (see its
     * {@code BookingOwnershipPort}) and now also to check the amount it was asked to charge
     * against the one recorded here. Returning only {@code status} left it with nothing to
     * compare against, so a caller could initiate a payment for their own booking at any amount
     * they liked. This response is the source of truth for that comparison.
     */
    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingStatusResponse> getBooking(@PathVariable("bookingId") String bookingId) {
        return getBookingUseCase.getBooking(bookingId)
                .map(b -> {
                    // bookingId alone is guessable/enumerable — without this, any authenticated
                    // caller could poll another customer's booking status by ID.
                    requireMatchingIdentity(b.getCustomerId());
                    return ResponseEntity.ok(new BookingStatusResponse(
                            b.getBookingId(), b.getStatus().name(),
                            b.getAmount().amount(), b.getAmount().currency()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * PUT /api/v1/bookings/{bookingId}/cancel — the customer cancels seats of their own booking
     * (FR-21). Body {@code {"seatCodes": ["A1"]}} names the seats; no body, or an empty list, means
     * every seat the booking still holds. An unpaid booking can only be cancelled whole; a paid one
     * seat by seat until 24 hours before kickoff, each seat refunded at its own price.
     *
     * <p>PUT, like match-catalog's publish/cancel/complete: the target state is fixed and a repeat
     * is a no-op, so the verb's idempotency promise actually holds — and it means nginx and the
     * gateway may safely retry it, unlike the POST that creates a booking.
     *
     * <p>The ownership check lives in the use case (bookingId is enumerable); this method only
     * says who is asking. Answers 200 with the booking as it now stands; 404 unknown id; 403 someone
     * else's; 422 for a DRAFT booking, seats that are not the booking's, part of an unpaid booking,
     * or a paid one past its deadline; 409 when another cancel of it is running or its tickets are
     * still being issued — both clear by themselves, so the 409s carry a Retry-After.
     */
    @PutMapping("/{bookingId}/cancel")
    public ResponseEntity<CancelBookingResponse> cancelBooking(
            @PathVariable("bookingId") String bookingId,
            @Valid @RequestBody(required = false) CancelBookingRequest request) {
        List<String> seatCodes = request == null || request.seatCodes() == null ? List.of() : request.seatCodes();
        Booking booking = cancelBookingUseCase.cancelBooking(
                new CancelBookingCommand(bookingId, currentUser().userId(), seatCodes));
        return ResponseEntity.ok(new CancelBookingResponse(
                booking.getBookingId(), booking.getStatus().name(),
                booking.getAmount().amount(), booking.getAmount().currency(),
                booking.activeSeatCodes(), booking.getCancelledSeatCodes(), booking.getRefundedAmount()));
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
                booking.getStatus().name(), booking.getCreatedAt(),
                booking.getCancelledSeatCodes(), booking.getRefundedAmount());
    }

    /**
     * Namespaces the client's Idempotency-Key under the account that presented it.
     *
     * <p>Both stores that key it — Redis's {@code booking:idempotency:<key>} and the
     * {@code ux_bookings_idempotency_key} unique index — took the header verbatim, so they were a
     * single namespace shared by every customer on the platform. A second customer presenting a
     * key a first customer had already completed was answered from the first customer's booking:
     * 201 Created carrying someone else's bookingId and status, with no booking of their own ever
     * created. Reaching that needed no attack, only a client that derives keys from anything but
     * a fresh random value — and an empty {@code Idempotency-Key:} header did it outright, since
     * the partial unique index treats {@code ''} as a value like any other. Blank is now absent.
     *
     * <p>The account id goes first and is taken from the validated JWT, never the request, so a
     * key crafted to look like another customer's prefix still lands under the caller's own.
     */
    private String scopedIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return currentUser().userId() + IDEMPOTENCY_KEY_SEPARATOR + idempotencyKey;
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
    /**
     * {@code seatCodes} is constrained element by element, not just non-empty: the format is
     * ticket-inventory-service's ({@code SeatCode}, e.g. A12, B3), and until it was checked here
     * nothing on booking's side checked it at all — {@code SeatSelection} deliberately holds only
     * shape-of-the-collection invariants, since its constructor also runs when reconstituting a
     * persisted booking. So an arbitrary string got as far as a saved booking row and a
     * confirmation email before inventory rejected it partway through the saga. Rejecting it at
     * the edge costs one annotation and turns that into a 400.
     */
    public record CreateBookingRequest(
            @NotBlank String customerId,
            @NotBlank String showtimeId,
            @NotEmpty List<@Pattern(regexp = SEAT_CODE_PATTERN,
                    message = "must be a seat code such as A12 or B3") String> seatCodes,
            @Positive BigDecimal amount,
            @NotBlank String currency
    ) {}

    public record CreateBookingResponse(String bookingId, String status) {}

    /**
     * {@code amount}/{@code currency} are the server-computed charge for this booking, never the
     * placeholder the client posted — see {@link #getBooking}. Only ever returned to the booking's
     * own customer (or an internal service acting for them), so exposing it here discloses
     * nothing the caller did not already own.
     */
    public record BookingStatusResponse(String bookingId, String status,
                                        BigDecimal amount, String currency) {}

    /**
     * {@code seatCodes} is every seat the booking was made for; {@code cancelledSeatCodes} the ones
     * it no longer holds (all of them once it is CANCELLED), and {@code refundedAmount} what has
     * been refunded for them — so the account page can show a part-cancelled booking as such.
     */
    public record BookingSummaryResponse(String bookingId, String showtimeId, List<String> seatCodes,
                                        BigDecimal amount, String currency, String status, Instant createdAt,
                                        List<String> cancelledSeatCodes, BigDecimal refundedAmount) {}

    /** {@code seatCodes} is optional; see {@link #cancelBooking}. Same format rule as creation. */
    public record CancelBookingRequest(
            List<@Pattern(regexp = SEAT_CODE_PATTERN,
                    message = "must be a seat code such as A12 or B3") String> seatCodes
    ) {}

    /**
     * The booking after the cancel. A superset of {@link BookingStatusResponse}, which stays as it
     * is: payment-service reads that one off GET and must not see it change shape.
     *
     * @param seatCodes          seats the booking still holds
     * @param cancelledSeatCodes seats it no longer holds
     * @param refundedAmount     refunds requested so far, in {@code currency}
     */
    public record CancelBookingResponse(String bookingId, String status, BigDecimal amount, String currency,
                                        List<String> seatCodes, List<String> cancelledSeatCodes,
                                        BigDecimal refundedAmount) {}

    public record BookingListResponse(List<BookingSummaryResponse> items, long totalElements, int page, int size) {}

    /**
     * A dependency this booking needs was unreachable, or said so itself → 503 Service Unavailable
     * with a Retry-After, not the 500 both of these used to collect from
     * {@code GlobalExceptionHandler}'s catch-all.
     *
     * <p>Two types, because there are two ways to learn it.
     * {@link OutboundServiceUnavailableException} is what both outbound adapters' fallbacks raise
     * when no usable answer came back at all — timeout, reset, open circuit, full bulkhead,
     * retries exhausted. {@code HttpServerErrorException.ServiceUnavailable} is a downstream that
     * answered 503 on its own: payment-service or ticket-inventory-service shedding load, or
     * inventory reporting that it could not reach match-catalog-service.
     * {@code initiatePaymentFallback} rethrows an {@code HttpStatusCodeException} unchanged, so it
     * arrives here as itself.
     *
     * <p>Deliberately narrow. A downstream 500 is not covered: that is not a temporary condition
     * the caller should be invited to wait out, and it has to keep reading as this platform
     * having failed. Nor is any 4xx, which is a definite answer about this particular request.
     *
     * <p>Logs the throwable because this is the last place that still holds one — the adapters'
     * fallbacks and the saga both log {@code getMessage()} only, so taking these exceptions off
     * {@code handleGenericException} would otherwise leave an outage with no stack trace in ELK.
     */
    @ExceptionHandler({OutboundServiceUnavailableException.class,
            HttpServerErrorException.ServiceUnavailable.class})
    public ResponseEntity<ProblemDetail> handleDownstreamUnavailable(Exception ex) {
        log.error("A service this booking needs is unavailable", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "A service this booking needs is temporarily unavailable");
        problem.setType(URI.create("https://aireak.com/errors/service-unavailable"));
        problem.setTitle("Service Unavailable");
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }

    /**
     * The three ways a cancel is turned away for now rather than refused: another cancel of the
     * booking holds the lock, the paid seats are still being issued, or the booking changed under
     * the request. Each has its own {@code type}, so the frontend can say which in Vietnamese, and
     * all three clear by themselves — hence 409 with a Retry-After, not a 422.
     */
    @ExceptionHandler({CancellationInProgressException.class, TicketIssuanceInProgressException.class,
            CancellationConflictException.class})
    public ResponseEntity<ProblemDetail> handleCancellationNotNow(RuntimeException ex) {
        String type = switch (ex) {
            case CancellationInProgressException ignored -> "cancellation-in-progress";
            case TicketIssuanceInProgressException ignored -> "ticket-issuance-in-progress";
            default -> "cancellation-conflict";
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://aireak.com/errors/" + type));
        problem.setTitle("Cancellation Not Possible Right Now");
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }

    /**
     * Still a 422 like every other DomainException, but with a {@code type} of its own: common's
     * handler gives them all {@code domain-error}, which the frontend cannot translate, and this is
     * a refusal the customer needs to understand rather than retry.
     */
    @ExceptionHandler(CancellationWindowClosedException.class)
    public ProblemDetail handleCancellationWindowClosed(CancellationWindowClosedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create("https://aireak.com/errors/cancellation-window-closed"));
        problem.setTitle("Cancellation Deadline Passed");
        problem.setProperty("cancellableUntil", ex.getClosedAt());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

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
