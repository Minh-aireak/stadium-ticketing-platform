package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.TicketInventoryPort;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins what ticket-inventory-service's answers actually cost booking-service, against the real
 * adapter and the real Resilience4j instance settings from {@code application.yaml} — the
 * annotations only do anything through a proxy, so asserting on a plain
 * {@code new TicketInventoryRestAdapter(...)} would pass no matter where they sat.
 *
 * <p>A {@code @CircuitBreaker(fallbackMethod = ...)} is applied by {@code FallbackExecutor}
 * OUTSIDE the circuit-breaker decoration and fires on any {@code Throwable}, not only on a
 * rejected call — while the Retry aspect (order 2147483642) sits outside the CircuitBreaker aspect
 * (2147483643). So the fallback ran on the first failure of every kind and replaced the exception
 * before the Retry aspect could match it against {@code ignoreExceptions}, which
 * {@code PredicateCreator.makePredicate} tests with a bare {@code isAssignableFrom} and no cause
 * traversal. Every entry in this service's retry ignore list was dead config.
 *
 * <p>A real HTTP server is used rather than a mocked RestClient because the status code is the
 * whole subject, and its request counter is the only thing that can prove how many times a
 * request actually went on the wire.
 */
@SpringBootTest(classes = TicketInventoryRestAdapterResilienceTest.Config.class, properties = {
        // Copied from booking-service/src/main/resources/application.yaml. waitDuration is the one
        // deliberate change (500ms -> 20ms): this test asserts on attempt counts, never on timing.
        "resilience4j.circuitbreaker.instances.ticket-inventory.slidingWindowSize=10",
        "resilience4j.circuitbreaker.instances.ticket-inventory.minimumNumberOfCalls=5",
        "resilience4j.circuitbreaker.instances.ticket-inventory.failureRateThreshold=50",
        "resilience4j.circuitbreaker.instances.ticket-inventory.waitDurationInOpenState=10s",
        "resilience4j.circuitbreaker.instances.ticket-inventory.permittedNumberOfCallsInHalfOpenState=3",
        "resilience4j.circuitbreaker.instances.ticket-inventory.ignoreExceptions[0]=io.github.resilience4j.bulkhead.BulkheadFullException",
        "resilience4j.bulkhead.instances.ticket-inventory.maxConcurrentCalls=50",
        "resilience4j.bulkhead.instances.ticket-inventory.maxWaitDuration=0",
        "resilience4j.retry.instances.ticket-inventory.maxAttempts=3",
        "resilience4j.retry.instances.ticket-inventory.waitDuration=20ms",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[0]=org.springframework.web.client.HttpClientErrorException",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[1]=org.springframework.web.client.HttpServerErrorException$ServiceUnavailable",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[2]=io.github.resilience4j.bulkhead.BulkheadFullException",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[3]=io.github.resilience4j.circuitbreaker.CallNotPermittedException"
})
class TicketInventoryRestAdapterResilienceTest {

    private static final StubTicketInventory STUB = StubTicketInventory.start();

    @DynamicPropertySource
    static void inventoryBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("services.ticket-inventory.base-url", () -> "http://127.0.0.1:" + STUB.port());
    }

    @Autowired
    private TicketInventoryPort ticketInventoryPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetSharedState() {
        // The context — and so the stub and the registry — is shared across methods here.
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("ticket-inventory");
        circuitBreaker.reset();
        STUB.reset();
    }

    /**
     * 503 is ticket-inventory-service explicitly shedding load (see its
     * {@code InventoryOverloadExceptionHandler}). The retry ignore list has always said so —
     * "retrying just adds to the pile-on" — and booking-service sent the request three times
     * anyway, tripling the load on the service that had just said it had none to spare.
     */
    @Test
    void aSheddingInventoryIsAskedOnceNotThreeTimes() {
        STUB.respondWith(503, "{\"detail\":\"Inventory is shedding load\"}");

        catchThrowable(() -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(STUB.requestCount()).isEqualTo(1);
    }

    /** The other half of that guard: a genuinely transient failure must still be retried. */
    @Test
    void aTransientFailureIsStillRetriedToExhaustion() {
        STUB.respondWith(500, "{\"detail\":\"boom\"}");

        catchThrowable(() -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(STUB.requestCount()).isEqualTo(3);
    }

    /**
     * releaseSeats is best-effort and swallows its own failures, but it carries {@code @Retry} and
     * that was inert: the fallback swallowed on attempt one, so the Retry aspect saw a successful
     * call and never made a second. A compensating release that lost one packet gave up on the
     * seats it existed to give back.
     */
    @Test
    void aFailedReleaseIsRetriedBeforeItIsGivenUpOn() {
        STUB.respondWith(500, "{\"detail\":\"boom\"}");

        assertThatCode(() -> ticketInventoryPort.releaseSeats("showtime-1", "booking-1", List.of("A1")))
                .doesNotThrowAnyException();

        assertThat(STUB.requestCount()).isEqualTo(3);
    }

    /** The opposite case, which must still count: a 503 means the service really is in trouble. */
    @Test
    void aSheddingInventoryStillCountsAgainstTheCircuit() {
        STUB.respondWith(503, "{\"detail\":\"Inventory is shedding load\"}");

        catchThrowable(() -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    /** Minimal stand-in for ticket-inventory-service: answers everything with one canned status. */
    static class StubTicketInventory {

        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile int status = 200;
        private volatile String body = "{}";

        private StubTicketInventory(HttpServer server) {
            this.server = server;
        }

        static StubTicketInventory start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                StubTicketInventory stub = new StubTicketInventory(server);
                server.createContext("/", exchange -> {
                    stub.requests.incrementAndGet();
                    byte[] payload = stub.body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
                    exchange.sendResponseHeaders(stub.status, payload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                });
                server.start();
                return stub;
            } catch (Exception e) {
                throw new IllegalStateException("Could not start the ticket-inventory stub", e);
            }
        }

        void respondWith(int status, String body) {
            this.status = status;
            this.body = body;
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

        @Primary
        @Bean("restClient")
        RestClient restClient() {
            return RestClient.builder().build();
        }

        @Bean
        InternalServiceTokenProvider internalServiceTokenProvider() {
            InternalServiceTokenProvider provider = mock(InternalServiceTokenProvider.class);
            when(provider.mintServiceToken()).thenReturn("internal-token");
            return provider;
        }

        @Bean
        TicketInventoryRestAdapter ticketInventoryRestAdapter(RestClient restClient,
                                                             InternalServiceTokenProvider provider) {
            return new TicketInventoryRestAdapter(restClient, provider);
        }
    }
}
