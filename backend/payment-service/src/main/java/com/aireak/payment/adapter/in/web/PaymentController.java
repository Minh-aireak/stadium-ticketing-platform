package com.aireak.payment.adapter.in.web;

import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import jakarta.validation.Valid;
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

    @PostMapping
    public ResponseEntity<InitiatePaymentResponse> initiate(@Valid @RequestBody InitiatePaymentRequest request) {
        String paymentId = initiatePaymentUseCase.execute(
                new InitiatePaymentCommand(request.bookingId(), request.amount(), request.currency()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InitiatePaymentResponse(paymentId));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<PaymentStatusResponse> getByBookingId(@PathVariable("bookingId") String bookingId) {
        return getPaymentUseCase.getByBookingId(bookingId)
                .map(p -> ResponseEntity.ok(new PaymentStatusResponse(
                        p.getPaymentId(), p.getBookingId(), p.getStatus().name(),
                        p.getGatewayTransactionId(), p.getFailureReason())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record InitiatePaymentRequest(
            @NotBlank String bookingId,
            @Positive BigDecimal amount,
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
