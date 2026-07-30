package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.OutboundServiceUnavailableException;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

// Outbound REST adapter: calls payment-service, decorated with @CircuitBreaker + @Retry.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRestAdapter implements PaymentPort {

    @Qualifier("restClient")
    private final RestClient restClient;

    // Short-timeout client, used only by checkOutcome — see InfraConfig#paymentStatusRestClient.
    @Qualifier("paymentStatusRestClient")
    private final RestClient paymentStatusRestClient;

    private final InternalServiceTokenProvider internalServiceTokenProvider;

    @Value("${services.payment.base-url:http://localhost:8084}")
    private String baseUrl;

    @Override
    @Bulkhead(name = "payment", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "payment", fallbackMethod = "initiatePaymentFallback")
    @Retry(name = "payment")
    public void initiatePayment(String bookingId, BigDecimal amount, String currency) {
        log.debug("Initiating payment: bookingId={}, amount={} {}", bookingId, amount, currency);
        // bookingId doubles as the Idempotency-Key so @Retry re-sends dedupe instead of
        // starting a second charge. Always called from within the caller's own HTTP request
        // (createBooking), so a real end-user token is always available to forward.
        restClient.post()
                .uri(baseUrl + "/api/v1/payments")
                .header("Idempotency-Key", bookingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationToken())
                .body(new InitiatePaymentRequest(bookingId, amount, currency))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Always the internal service token, never a forwarded end-user token — this only ever runs
     * from {@code MatchCancelledConsumer} (Kafka listener thread), which has no authenticated
     * end-user request to forward (see TicketInventoryRestAdapter#confirmReservation, same
     * rationale). Best-effort: a failure here is logged and swallowed so it never blocks the
     * booking's own cancellation — payment-service's refund endpoint has no reconciliation
     * backstop yet (see PaymentService#refundByBookingId javadoc), so a swallowed failure here is
     * a real, currently-accepted gap, not a false sense of safety.
     */
    @Override
    @Bulkhead(name = "payment", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "payment", fallbackMethod = "refundPaymentFallback")
    @Retry(name = "payment")
    public void refundPayment(String bookingId, String reason) {
        log.debug("Refunding payment: bookingId={}", bookingId);
        restClient.post()
                .uri(baseUrl + "/api/v1/payments/{bookingId}/refund", bookingId)
                .header("Idempotency-Key", "refund:" + bookingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalServiceTokenProvider.mintServiceToken())
                .body(new RefundRequest(reason))
                .retrieve()
                .toBodilessEntity();
    }

    private void refundPaymentFallback(String bookingId, String reason, Throwable t) {
        log.error("Failed to refund payment (non-critical, no reconciliation backstop yet): bookingId={}: {}",
                bookingId, t.getMessage());
    }

    // See TicketInventoryRestAdapter#authorizationToken — same fallback rationale.
    private String authorizationToken() {
        return AuthenticatedUserContext.get().map(AuthenticatedUser::token)
                .orElseGet(internalServiceTokenProvider::mintServiceToken);
    }

    private void initiatePaymentFallback(String bookingId, BigDecimal amount,
                                          String currency, Throwable t) {
        log.error("Circuit open / retry exhausted for initiatePayment bookingId={}: {}",
                bookingId, t.getMessage());
        // Definite HTTP response — surface as-is so BookingOrchestrationService can branch
        // on exception type directly instead of unwrapping getCause().
        if (t instanceof HttpStatusCodeException httpEx) {
            throw httpEx;
        }
        throw new OutboundServiceUnavailableException("Payment service unavailable", t);
    }

    // No @CircuitBreaker/@Retry: this only fires after an already-ambiguous initiatePayment
    // failure, so retrying would just add latency to an already-degraded call. Every failure
    // mode here degrades to Optional.empty() (caller already treats that as "stay ambiguous"),
    // and the scheduled reconciliation job is the real backstop — paymentStatusRestClient's
    // short timeouts (InfraConfig) just bound how much latency this can add.
    @Override
    public Optional<PaymentOutcome> checkOutcome(String bookingId) {
        try {
            PaymentStatusResponse response = paymentStatusRestClient.get()
                    .uri(baseUrl + "/api/v1/payments/{bookingId}", bookingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationToken())
                    .retrieve()
                    .body(PaymentStatusResponse.class);
            if (response == null) {
                return Optional.empty();
            }
            return switch (response.status()) {
                case "SUCCEEDED" -> Optional.of(PaymentOutcome.SUCCEEDED);
                case "FAILED" -> Optional.of(PaymentOutcome.FAILED);
                // INITIATED (still in flight) or anything else — not a terminal outcome.
                default -> Optional.empty();
            };
        } catch (Exception e) {
            // Includes 404: indistinguishable from "still mid-flight, not committed yet" — never a signal.
            log.warn("Payment status reconciliation query failed for booking {}: {}",
                    bookingId, e.getMessage());
            return Optional.empty();
        }
    }

    record InitiatePaymentRequest(String bookingId, BigDecimal amount, String currency) {}

    record RefundRequest(String reason) {}

    record PaymentStatusResponse(String paymentId, String bookingId, String status,
                                  String gatewayTransactionId, String failureReason) {}
}
