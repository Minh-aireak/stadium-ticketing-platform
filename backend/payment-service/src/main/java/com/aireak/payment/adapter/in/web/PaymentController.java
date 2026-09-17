package com.aireak.payment.adapter.in.web;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.RetryPaymentUseCase;
import com.aireak.payment.application.port.in.SyncPaymentUseCase;
import com.aireak.payment.config.PaymentModeProperties;
import com.aireak.payment.domain.model.PaymentStatus;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.application.port.out.BookingOwnershipPort;
import com.aireak.payment.domain.exception.DuplicatePaymentException;
import com.aireak.payment.domain.exception.PaymentAmountMismatchException;
import com.aireak.payment.domain.model.Payment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/** Inbound REST adapter: payment initiation and status lookup endpoints. */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final InitiatePaymentUseCase initiatePaymentUseCase;
    private final GetPaymentUseCase getPaymentUseCase;
    private final RetryPaymentUseCase retryPaymentUseCase;
    private final SyncPaymentUseCase syncPaymentUseCase;
    private final BookingOwnershipPort bookingOwnershipPort;
    private final PaymentModeProperties paymentMode;

    /**
     * POST /api/v1/payments — starts the charge for a booking.
     *
     * <p>Owning the booking is necessary but <strong>not sufficient</strong>: the charge is only
     * ever the amount booking-service computed server-side from the seats' tiers. Trusting
     * {@code request.amount()} meant a customer who owned a booking could pay whatever they liked
     * for it — reachable in practice by forcing booking-service's Step 4 to fail ambiguously (its
     * payment circuit breaker opening is enough), which leaves the booking PENDING_PAYMENT with no
     * Payment row, then calling this endpoint directly with the bookingId from "my tickets".
     */
    @PostMapping
    public ResponseEntity<InitiatePaymentResponse> initiate(@Valid @RequestBody InitiatePaymentRequest request) {
        BigDecimal chargeAmount = request.amount();
        String chargeCurrency = request.currency();

        AuthenticatedUser caller = currentUser();
        if (!caller.isInternalService()) {
            BookingOwnershipPort.OwnedBooking booking =
                    bookingOwnershipPort.fetchOwnedBooking(request.bookingId(), caller.token());
            requireAmountMatchesBooking(request, booking);
            // Charge the booking's own figures, not the request's — matching them above makes the
            // two equal, so this is belt-and-braces against a future divergence in that check.
            chargeAmount = booking.amount();
            chargeCurrency = booking.currency();
        }

        try {
            // Email comes off the validated JWT, never the request body — the ownership check
            // above already proved this caller owns the booking, and a client-supplied address
            // would let anyone redirect someone else's payment receipt.
            String paymentId = initiatePaymentUseCase.execute(
                    new InitiatePaymentCommand(request.bookingId(), currentUser().email(),
                            chargeAmount, chargeCurrency));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new InitiatePaymentResponse(paymentId));
        } catch (DuplicatePaymentException exception) {
            // The Redis idempotency owner may still be between acquiring its key and committing
            // the Payment row. This is an accepted in-flight request, not a validation failure:
            // returning 202 prevents an idempotent retry from cancelling the booking while the
            // original gateway charge continues in the background.
            //
            // This — not the 422 the exception would otherwise collect from GlobalExceptionHandler
            // — is what booking-service actually sees, so it is the only thing standing between a
            // concurrent attempt and a cancelled booking. Its Step 4 does carry a second net that
            // reads the 422 wording (BookingOrchestrationService#isPaymentAlreadyBeingProcessed),
            // but that net has never had a sender; removing this catch is what would wake it up.
            return ResponseEntity.accepted().body(new InitiatePaymentResponse(null));
        }
    }

    /** POST /api/v1/payments/{paymentId}/retry — re-attempts a FAILED payment on the same row. */
    @PostMapping("/{paymentId}/retry")
    public ResponseEntity<InitiatePaymentResponse> retry(@PathVariable("paymentId") String paymentId) {
        // Resolve paymentId → bookingId so we can verify ownership before touching the payment.
        Payment payment = getPaymentUseCase.getById(paymentId).orElse(null);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        enforceBookingOwnership(payment.getBookingId());
        return retryPaymentUseCase.retry(paymentId)
                .map(id -> ResponseEntity.ok(new InitiatePaymentResponse(id)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private AuthenticatedUser currentUser() {
        return AuthenticatedUserContext.get()
                .orElseThrow(() -> new IllegalStateException(
                        "JwtAuthenticationFilter did not run for this request"));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<PaymentStatusResponse> getByBookingId(@PathVariable("bookingId") String bookingId) {
        enforceBookingOwnership(bookingId);
        return getPaymentUseCase.getByBookingId(bookingId)
                .map(p -> ResponseEntity.ok(toResponse(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/payments/{bookingId}/sync — card mode: the storefront has just watched Stripe.js
     * confirm the intent and asks this side to go and read the outcome from the gateway. The
     * request body carries nothing and the browser's opinion is not consulted; the answer is
     * whatever the gateway says now, recorded through the same steps a webhook would use. Safe to
     * repeat, and a no-op for anything that is not an open card payment.
     */
    @PostMapping("/{bookingId}/sync")
    public ResponseEntity<PaymentStatusResponse> sync(@PathVariable("bookingId") String bookingId) {
        enforceBookingOwnership(bookingId);
        return syncPaymentUseCase.syncWithGateway(bookingId)
                .map(p -> ResponseEntity.ok(toResponse(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The client secret and the deadline travel only while there is something for the customer to
     * do with them: an INITIATED card-mode payment. Ownership was enforced by the caller, so the
     * secret reaches the one browser entitled to confirm the intent and nobody else -- an
     * internal-service caller (booking-service's reconciliation) gets it too, and does nothing with it.
     */
    private PaymentStatusResponse toResponse(Payment p) {
        boolean awaitingCustomer = p.isCardMode() && p.getStatus() == PaymentStatus.INITIATED;
        return new PaymentStatusResponse(
                p.getPaymentId(), p.getBookingId(), p.getStatus().name(),
                p.getGatewayTransactionId(), p.getFailureReason(),
                awaitingCustomer ? p.getClientSecret() : null,
                awaitingCustomer ? p.getCreatedAt().plus(paymentMode.window()) : null);
    }

    /**
     * If the caller is an internal service (reconciliation job, booking-service saga step) the
     * ownership check is skipped — those calls operate on behalf of the system, not an end-user.
     * Otherwise, forwards the caller's original bearer token to booking-service's
     * {@code GET /api/v1/bookings/{bookingId}}, which enforces ownership itself (403/404 for
     * non-owners), so payment-service never duplicates that logic.
     */
    private void enforceBookingOwnership(String bookingId) {
        AuthenticatedUser user = currentUser();
        if (user.isInternalService()) {
            return;
        }
        bookingOwnershipPort.fetchOwnedBooking(bookingId, user.token());
    }

    /**
     * Rejects a request whose amount/currency disagree with the booking's own.
     *
     * <p>Fails closed when booking-service did not report them at all: an older instance that
     * doesn't yet return the fields leaves them null, and "we could not check" must never be
     * allowed to read as "it checked out" — that is precisely the state this endpoint was
     * previously in for every request.
     */
    private void requireAmountMatchesBooking(InitiatePaymentRequest request,
                                             BookingOwnershipPort.OwnedBooking booking) {
        if (booking.amount() == null || booking.currency() == null) {
            throw new IllegalStateException(
                    "booking-service did not report an authoritative amount for booking " + booking.bookingId()
                            + "; refusing to charge an unverified amount");
        }
        // compareTo, not equals: BigDecimal.equals also compares scale, so 500 and 500.00 would
        // be rejected as a mismatch even though they are the same amount of money.
        if (request.amount().compareTo(booking.amount()) != 0
                || !booking.currency().equalsIgnoreCase(request.currency())) {
            throw new PaymentAmountMismatchException(
                    "Requested charge does not match the booking's amount");
        }
    }

    // fraction = 2 mirrors the `amount` column's own precision(15,2) and rejects sub-unit values
    // before they can round unpredictably at the gateway. It is not a claim that every currency
    // has two decimals: StripeGatewayAdapter#toSmallestUnit knows fifteen that have none, and
    // this deployment's own pricing currency is one of them (inventory.pricing.currency defaults
    // to VND). What keeps a fractional VND amount from ever reaching longValueExact() is upstream
    // -- SeatMapLayout#priceFor ends in setScale(0, HALF_UP), so every seat price, and therefore
    // every booking total, is a whole number -- plus requireAmountMatchesBooking below, which
    // refuses to charge anything but the booking's own recorded amount.
    record InitiatePaymentRequest(
            @NotBlank String bookingId,
            @Positive @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotBlank String currency
    ) {}

    record InitiatePaymentResponse(String paymentId) {}

    /**
     * @param clientSecret card mode, while INITIATED: what Stripe.js needs to confirm the intent
     * @param expiresAt    card mode, while INITIATED: when the payment window closes and the seats
     *                     go back on sale (see PaymentWindowExpiryJob); the storefront's countdown
     */
    record PaymentStatusResponse(
            String paymentId,
            String bookingId,
            String status,
            String gatewayTransactionId,
            String failureReason,
            String clientSecret,
            Instant expiresAt
    ) {}
}
