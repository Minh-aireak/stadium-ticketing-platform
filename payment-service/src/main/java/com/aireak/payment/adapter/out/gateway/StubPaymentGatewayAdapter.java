package com.aireak.payment.adapter.out.gateway;

import com.aireak.payment.application.port.out.PaymentGatewayPort;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stub payment gateway adapter.
 * Replace with VNPay/Stripe SDK integration.
 *
 * <p>@Bulkhead + @CircuitBreaker + @Retry applied here — hexagonal rule: only adapter layer
 * uses Resilience4j annotations.
 */
@Slf4j
@Component
public class StubPaymentGatewayAdapter implements PaymentGatewayPort {

    @Override
    @Bulkhead(name = "payment-gateway", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "payment-gateway", fallbackMethod = "chargeFallback")
    @Retry(name = "payment-gateway")
    public String charge(String bookingId, BigDecimal amount, String currency) {
        // STUB — always succeeds with a fake transaction ID
        String gatewayTxId = "GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[STUB GATEWAY] Charged: bookingId={}, amount={} {}, txId={}",
                bookingId, amount, currency, gatewayTxId);
        return gatewayTxId;
    }

    private String chargeFallback(String bookingId, BigDecimal amount, String currency, Throwable t) {
        log.error("Payment gateway unavailable for bookingId={}: {}", bookingId, t.getMessage());
        throw new RuntimeException("Payment gateway unavailable", t);
    }
}
