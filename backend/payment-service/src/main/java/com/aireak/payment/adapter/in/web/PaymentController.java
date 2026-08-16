package com.aireak.payment.adapter.in.web;

import com.aireak.common.exception.ForbiddenException;
import com.aireak.common.exception.IdentityMismatchException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.RefundPaymentUseCase;
import com.aireak.payment.application.port.in.RetryPaymentUseCase;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.application.port.out.BookingOwnershipPort;
import com.aireak.payment.domain.exception.DuplicatePaymentException;
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

/** Inbound REST adapter: payment initiation and status lookup endpoints. */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final InitiatePaymentUseCase initiatePaymentUseCase;
    private final GetPaymentUseCase getPaymentUseCase;
    private final RetryPaymentUseCase retryPaymentUseCase;
    private final RefundPaymentUseCase refundPaymentUseCase;
    private final BookingOwnershipPort bookingOwnershipPort;

    @PostMapping
    public ResponseEntity<InitiatePaymentResponse> initiate(@Valid @RequestBody InitiatePaymentRequest request) {
        enforceBookingOwnership(request.bookingId());
        try {
            // Email comes off the validated JWT, never the request body — enforceBookingOwnership
            // above already proved this caller owns the booking, and a client-supplied address
            // would let anyone redirect someone else's payment receipt.
            String paymentId = initiatePaymentUseCase.execute(
                    new InitiatePaymentCommand(request.bookingId(), currentUser().email(),
                            request.amount(), request.currency()));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new InitiatePaymentResponse(paymentId));
        } catch (DuplicatePaymentException exception) {
            // The Redis idempotency owner may still be between acquiring its key and committing
            // the Payment row. This is an accepted in-flight request, not a validation failure:
            // returning 202 prevents an idempotent retry from cancelling the booking while the
            // original gateway charge continues in the background.
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

    /**
     * POST /api/v1/payments/{bookingId}/refund — internal-service token only:
     * {@code booking-service}'s {@code PaymentRestAdapter} never forwards a real customer token
     * for this call (it always mints one — see {@code InternalServiceTokenProvider}), so a
     * request bearing a customer token here cannot be a genuine system-triggered refund. Mirrors
     * ticket-inventory-service's {@code SeatInventoryController#confirm} pattern.
     */
    @PostMapping("/{bookingId}/refund")
    public ResponseEntity<RefundResponse> refund(@PathVariable("bookingId") String bookingId,
                                                 @Valid @RequestBody RefundRequest request) {
        requireInternalService();
        return refundPaymentUseCase.refundByBookingId(bookingId, request.reason())
                .map(paymentId -> ResponseEntity.ok(new RefundResponse(paymentId, true)))
                .orElseGet(() -> ResponseEntity.ok(new RefundResponse(null, false)));
    }

    private AuthenticatedUser currentUser() {
        return AuthenticatedUserContext.get()
                .orElseThrow(() -> new IllegalStateException(
                        "JwtAuthenticationFilter did not run for this request"));
    }

    private void requireInternalService() {
        AuthenticatedUser user = currentUser();
        if (!user.isInternalService()) {
            throw new ForbiddenException("This operation is restricted to internal service calls");
        }
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<PaymentStatusResponse> getByBookingId(@PathVariable("bookingId") String bookingId) {
        enforceBookingOwnership(bookingId);
        return getPaymentUseCase.getByBookingId(bookingId)
                .map(p -> ResponseEntity.ok(new PaymentStatusResponse(
                        p.getPaymentId(), p.getBookingId(), p.getStatus().name(),
                        p.getGatewayTransactionId(), p.getFailureReason())))
                .orElseGet(() -> ResponseEntity.notFound().build());
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
        bookingOwnershipPort.verifyCallerOwnsBooking(bookingId, user.token());
    }

    // fraction = 2 for every currency (simplification: no zero-decimal currency support like
    // JPY yet) — mirrors the `amount` column's own precision(15,2), and rejects sub-cent values
    // before they can round unpredictably or blow up BigDecimal division at the gateway.
    record InitiatePaymentRequest(
            @NotBlank String bookingId,
            @Positive @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotBlank String currency
    ) {}

    record InitiatePaymentResponse(String paymentId) {}

    record RefundRequest(@NotBlank String reason) {}

    record RefundResponse(String paymentId, boolean refunded) {}

    record PaymentStatusResponse(
            String paymentId,
            String bookingId,
            String status,
            String gatewayTransactionId,
            String failureReason
    ) {}
}
