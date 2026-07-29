package com.aireak.payment.adapter.in.web;

import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.RetryPaymentUseCase;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
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

    @PostMapping
    public ResponseEntity<InitiatePaymentResponse> initiate(@Valid @RequestBody InitiatePaymentRequest request) {
        String paymentId = initiatePaymentUseCase.execute(
                new InitiatePaymentCommand(request.bookingId(), request.amount(), request.currency()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InitiatePaymentResponse(paymentId));
    }

    /** POST /api/v1/payments/{paymentId}/retry — re-attempts a FAILED payment on the same row. */
    @PostMapping("/{paymentId}/retry")
    public ResponseEntity<InitiatePaymentResponse> retry(@PathVariable("paymentId") String paymentId) {
        return retryPaymentUseCase.retry(paymentId)
                .map(id -> ResponseEntity.ok(new InitiatePaymentResponse(id)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<PaymentStatusResponse> getByBookingId(@PathVariable("bookingId") String bookingId) {
        return getPaymentUseCase.getByBookingId(bookingId)
                .map(p -> ResponseEntity.ok(new PaymentStatusResponse(
                        p.getPaymentId(), p.getBookingId(), p.getStatus().name(),
                        p.getGatewayTransactionId(), p.getFailureReason())))
                .orElseGet(() -> ResponseEntity.notFound().build());
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

    record PaymentStatusResponse(
            String paymentId,
            String bookingId,
            String status,
            String gatewayTransactionId,
            String failureReason
    ) {}
}
