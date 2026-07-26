package com.aireak.payment.adapter.in.web;

import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** Inbound REST adapter: payment initiation endpoint. */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final InitiatePaymentUseCase initiatePaymentUseCase;

    @PostMapping
    public ResponseEntity<InitiatePaymentResponse> initiate(@Valid @RequestBody InitiatePaymentRequest request) {
        String paymentId = initiatePaymentUseCase.execute(
                new InitiatePaymentCommand(request.bookingId(), request.amount(), request.currency()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InitiatePaymentResponse(paymentId));
    }

    record InitiatePaymentRequest(
            @NotBlank String bookingId,
            @Positive BigDecimal amount,
            @NotBlank String currency
    ) {}

    record InitiatePaymentResponse(String paymentId) {}
}
