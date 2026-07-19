package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.PaymentPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Outbound REST adapter: calls payment-service to initiate payment.
 * Decorated with @CircuitBreaker + @Retry (resilience4j-spring-boot4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRestAdapter implements PaymentPort {

    private final RestClient restClient;

    @Value("${services.payment.base-url:http://localhost:8084}")
    private String baseUrl;

    @Override
    @CircuitBreaker(name = "payment", fallbackMethod = "initiatePaymentFallback")
    @Retry(name = "payment")
    public void initiatePayment(String bookingId, BigDecimal amount, String currency) {
        log.debug("Initiating payment: bookingId={}, amount={} {}", bookingId, amount, currency);
        restClient.post()
                .uri(baseUrl + "/api/v1/payments")
                .body(new InitiatePaymentRequest(bookingId, amount, currency))
                .retrieve()
                .toBodilessEntity();
    }

    private void initiatePaymentFallback(String bookingId, BigDecimal amount,
                                          String currency, Throwable t) {
        log.error("Circuit open / retry exhausted for initiatePayment bookingId={}: {}",
                bookingId, t.getMessage());
        throw new RuntimeException("Payment service unavailable", t);
    }

    record InitiatePaymentRequest(String bookingId, BigDecimal amount, String currency) {}
}
