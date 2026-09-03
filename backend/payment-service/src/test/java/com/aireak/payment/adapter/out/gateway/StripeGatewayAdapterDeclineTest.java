package com.aireak.payment.adapter.out.gateway;

import com.aireak.payment.application.port.out.PaymentDeclinedException;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.stripe.Stripe;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Pins what a declined card costs, against the real {@link StripeGatewayAdapter} behind its real
 * annotations and the real Resilience4j settings from {@code application.yaml}. The Stripe SDK is
 * pointed at a local stub via {@code Stripe.overrideApiBase} so a genuine {@code CardException}
 * comes back through the SDK's own error handling — a mocked {@code PaymentGatewayPort} would
 * bypass the adapter, which is where the defect lived.
 *
 * <p>A {@code @CircuitBreaker(fallbackMethod = ...)} is applied OUTSIDE the circuit-breaker
 * decoration and fires on any {@code Throwable}, not only on a rejected call. So
 * {@code chargeFallback} ran on the very first decline and replaced
 * {@link PaymentDeclinedException} with a wrapper. The circuit breaker's own
 * {@code ignoreExceptions} still worked (it sees the raw exception), but the Retry aspect sits
 * OUTSIDE the CircuitBreaker aspect and by then only had the wrapper to match its own
 * {@code ignoreExceptions} against — so it charged the declined card a second time.
 */
@SpringBootTest(classes = StripeGatewayAdapterDeclineTest.Config.class, properties = {
        "stripe.secret-key=sk_test_stub",
        // Copied from payment-service/src/main/resources/application.yaml. waitDuration is the one
        // deliberate change (1s -> 20ms): this test asserts on attempt counts, never on timing.
        "resilience4j.circuitbreaker.instances.payment-gateway.slidingWindowSize=10",
        "resilience4j.circuitbreaker.instances.payment-gateway.failureRateThreshold=50",
        "resilience4j.circuitbreaker.instances.payment-gateway.waitDurationInOpenState=30s",
        "resilience4j.circuitbreaker.instances.payment-gateway.permittedNumberOfCallsInHalfOpenState=2",
        "resilience4j.circuitbreaker.instances.payment-gateway.ignoreExceptions[0]=io.github.resilience4j.bulkhead.BulkheadFullException",
        "resilience4j.circuitbreaker.instances.payment-gateway.ignoreExceptions[1]=com.aireak.payment.application.port.out.PaymentDeclinedException",
        "resilience4j.bulkhead.instances.payment-gateway.maxConcurrentCalls=30",
        "resilience4j.bulkhead.instances.payment-gateway.maxWaitDuration=0",
        "resilience4j.retry.instances.payment-gateway.maxAttempts=2",
        "resilience4j.retry.instances.payment-gateway.waitDuration=20ms",
        "resilience4j.retry.instances.payment-gateway.ignoreExceptions[0]=com.aireak.payment.application.port.out.PaymentDeclinedException",
        "resilience4j.retry.instances.payment-gateway.ignoreExceptions[1]=io.github.resilience4j.bulkhead.BulkheadFullException",
        "resilience4j.retry.instances.payment-gateway.ignoreExceptions[2]=io.github.resilience4j.circuitbreaker.CallNotPermittedException"
})
class StripeGatewayAdapterDeclineTest {

    private static final String DECLINE_MESSAGE = "Your card was declined.";

    private static final StubStripe STUB = StubStripe.start();

    private static String originalApiBase;
    private static int originalMaxNetworkRetries;

    @BeforeAll
    static void pointTheSdkAtTheStub() {
        originalApiBase = Stripe.getApiBase();
        originalMaxNetworkRetries = Stripe.getMaxNetworkRetries();
        Stripe.overrideApiBase("http://127.0.0.1:" + STUB.port());
        // The SDK retries on its own (default 2) on top of Resilience4j; zero here so the request
        // counter below measures only what the adapter's own @Retry decided to do.
        Stripe.setMaxNetworkRetries(0);
    }

    @AfterAll
    static void restoreTheSdk() {
        Stripe.overrideApiBase(originalApiBase);
        Stripe.setMaxNetworkRetries(originalMaxNetworkRetries);
    }

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetSharedState() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("payment-gateway");
        circuitBreaker.reset();
        STUB.reset();
    }

    /**
     * Stripe replays the stored response for a repeated idempotency key, so a retried decline can
     * only ever fetch the same decline back — which is what the retry ignore list has always said.
     * It never matched, so every declined card was charged twice.
     */
    @Test
    void aDeclinedCardIsNotPresentedToTheGatewayASecondTime() {
        STUB.respondWithCardDecline();

        catchThrowable(() -> paymentGatewayPort.charge("booking-1", "booking-1",
                new BigDecimal("150.00"), "USD"));

        assertThat(STUB.requestCount()).isEqualTo(1);
    }

    /** Already true before this change, and pinned so it stays true: a decline is not a fault. */
    @Test
    void aDeclinedCardDoesNotCountAgainstTheGatewayCircuit() {
        STUB.respondWithCardDecline();

        catchThrowable(() -> paymentGatewayPort.charge("booking-1", "booking-1",
                new BigDecimal("150.00"), "USD"));

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    /**
     * The fallback still has to do its job for a real gateway fault: a 500 is retried to
     * exhaustion and then reported as the gateway being unavailable, so
     * {@code PaymentService#execute} records it as an AMBIGUOUS failure rather than a decline.
     */
    @Test
    void aGatewayFaultIsStillRetriedAndReportedAsUnavailable() {
        STUB.respondWithServerError();

        Throwable thrown = catchThrowable(
                () -> paymentGatewayPort.charge("booking-1", "booking-1", new BigDecimal("150.00"), "USD"));

        assertThat(STUB.requestCount()).isEqualTo(2);
        assertThat(thrown).isNotInstanceOf(PaymentDeclinedException.class);
        assertThat(thrown).hasMessage("Payment gateway unavailable");
    }

    /** Minimal stand-in for the Stripe API: answers every call with one canned error body. */
    static class StubStripe {

        private static final String CARD_DECLINE_BODY = """
                {"error":{"type":"card_error","code":"card_declined",\
                "decline_code":"generic_decline","message":"%s"}}""".formatted(DECLINE_MESSAGE);

        private static final String SERVER_ERROR_BODY =
                "{\"error\":{\"type\":\"api_error\",\"message\":\"Something went wrong\"}}";

        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile int status = 402;
        private volatile String body = CARD_DECLINE_BODY;

        private StubStripe(HttpServer server) {
            this.server = server;
        }

        static StubStripe start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                StubStripe stub = new StubStripe(server);
                server.createContext("/", exchange -> {
                    stub.requests.incrementAndGet();
                    byte[] payload = stub.body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(stub.status, payload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                });
                server.start();
                return stub;
            } catch (Exception e) {
                throw new IllegalStateException("Could not start the Stripe stub", e);
            }
        }

        void respondWithCardDecline() {
            this.status = 402;
            this.body = CARD_DECLINE_BODY;
        }

        void respondWithServerError() {
            this.status = 500;
            this.body = SERVER_ERROR_BODY;
        }

        void reset() {
            requests.set(0);
        }

        int requestCount() {
            return requests.get();
        }

        int port() {
            return server.getAddress().getPort();
        }
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({AopAutoConfiguration.class, PropertyPlaceholderAutoConfiguration.class,
            CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class, BulkheadAutoConfiguration.class})
    static class Config {

        @Bean
        StripeGatewayAdapter stripeGatewayAdapter() {
            return new StripeGatewayAdapter();
        }
    }
}
