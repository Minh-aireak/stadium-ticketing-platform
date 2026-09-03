package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.OutboundServiceUnavailableException;
import com.aireak.booking.application.port.out.SeatReservationRejectedException;
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
 * <p>Two separate things are pinned here, and both were wrong because of one detail: a
 * {@code @CircuitBreaker(fallbackMethod = ...)} is applied by {@code FallbackExecutor} OUTSIDE the
 * circuit-breaker decoration and fires on any {@code Throwable}, not only on a rejected call —
 * while the Retry aspect (order 2147483642) sits outside the CircuitBreaker aspect (2147483643).
 * So the fallback ran on the first failure of every kind and replaced the exception before the
 * Retry aspect could match it against {@code ignoreExceptions}, which
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
        "resilience4j.circuitbreaker.instances.ticket-inventory.ignoreExceptions[1]=com.aireak.booking.application.port.out.SeatReservationRejectedException",
        "resilience4j.circuitbreaker.instances.ticket-inventory.ignoreExceptions[2]=com.aireak.booking.application.port.out.InventoryConfirmationRefusedException",
        "resilience4j.bulkhead.instances.ticket-inventory.maxConcurrentCalls=50",
        "resilience4j.bulkhead.instances.ticket-inventory.maxWaitDuration=0",
        "resilience4j.retry.instances.ticket-inventory.maxAttempts=3",
        "resilience4j.retry.instances.ticket-inventory.waitDuration=20ms",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[0]=org.springframework.web.client.HttpClientErrorException",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[1]=org.springframework.web.client.HttpServerErrorException$ServiceUnavailable",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[2]=io.github.resilience4j.bulkhead.BulkheadFullException",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[3]=io.github.resilience4j.circuitbreaker.CallNotPermittedException",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[4]=com.aireak.booking.application.port.out.SeatReservationRejectedException",
        "resilience4j.retry.instances.ticket-inventory.ignoreExceptions[5]=com.aireak.booking.application.port.out.InventoryConfirmationRefusedException"
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

    /**
     * 422 is what ticket-inventory-service answers when the seats are gone or the booking window
     * has closed — {@code SeatsNotAvailableException}/{@code ShowtimeBookingClosedException} are
     * {@code DomainException}s, which {@code GlobalExceptionHandler} maps to 422. That is the
     * service answering, not the service failing, so it must not be retried.
     */
    @Test
    void aSeatSomeoneElseTookIsAskedForOnceOnly() {
        STUB.respondWith(422, "{\"detail\":\"Seats not available for showtime showtime-1: A1\"}");

        catchThrowable(() -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(STUB.requestCount()).isEqualTo(1);
    }

    /**
     * ...and it has to keep meaning what it meant. It used to reach the saga as a bare
     * {@code RuntimeException("Ticket inventory service unavailable")}, which
     * {@code GlobalExceptionHandler#handleGenericException} turns into a 500 — so a customer whose
     * hold had lapsed while they were on the checkout page was told the platform had broken.
     */
    @Test
    void aSeatSomeoneElseTookKeepsSayingSo() {
        STUB.respondWith(422, "{\"detail\":\"Seats not available for showtime showtime-1: A1\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isInstanceOf(SeatReservationRejectedException.class);
        assertThat(thrown).hasMessageContaining("Seats not available for showtime showtime-1: A1");
    }

    /**
     * The one that takes the buy path down. Contention for a seat is normal traffic on a hot
     * match, and every 422 counted as ticket-inventory-service being at fault — three times over,
     * once per dead-ignore-list retry. Two customers losing the same seat race were enough to open
     * the breaker (5 failures against minimumNumberOfCalls=5, failureRateThreshold=50), and an
     * open breaker takes reserveSeats, releaseSeats and the post-payment confirmReservation with
     * it. payment-service already guards this exact shape for card declines; this side did not.
     */
    @Test
    void seatContentionDoesNotOpenTheCircuitOnAServiceThatAnsweredCorrectly() {
        STUB.respondWith(422, "{\"detail\":\"Seats not available for showtime showtime-1: A1\"}");

        for (int i = 0; i < 6; i++) {
            catchThrowable(() -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** The opposite case, which must still count: a 503 means the service really is in trouble. */
    @Test
    void aSheddingInventoryStillCountsAgainstTheCircuit() {
        STUB.respondWith(503, "{\"detail\":\"Inventory is shedding load\"}");

        catchThrowable(() -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    /**
     * The counterpart to {@link #aSeatSomeoneElseTookKeepsSayingSo}. That one fixed the 422
     * branch; every other failure still left here as a bare {@code RuntimeException}, a type
     * nothing can map, so {@code GlobalExceptionHandler#handleGenericException} answered the
     * customer 500 "An unexpected error occurred" whenever ticket-inventory-service was simply
     * unreachable. A typed exception is what lets BookingController answer 503 instead.
     */
    @Test
    void anUnreachableInventoryIsReportedAsUnavailableNotAsABareRuntimeException() {
        STUB.respondWith(503, "{\"detail\":\"Inventory is shedding load\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isInstanceOf(OutboundServiceUnavailableException.class);
    }

    /**
     * ticket-inventory-service answers 404 when a showtime has no seat inventory at all. That is a
     * definite answer about these seats from a healthy service -- the same category as the 422 --
     * so it must reach the customer as a refusal rather than as this service having broken.
     */
    @Test
    void aShowtimeWithNoInventoryIsARefusalNotAFailure() {
        STUB.respondWith(404, "{\"detail\":\"SeatInventory not found for showtime: showtime-1\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isInstanceOf(SeatReservationRejectedException.class);
    }

    /** ...and, like the 422, it must not count against a service that answered correctly. */
    @Test
    void aShowtimeWithNoInventoryDoesNotOpenTheCircuit() {
        STUB.respondWith(404, "{\"detail\":\"SeatInventory not found for showtime: showtime-1\"}");

        for (int i = 0; i < 6; i++) {
            catchThrowable(() -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** Retry-exhausted 500s are the same story: no usable answer ever came back. */
    @Test
    void anInventoryThatKeepsFailingIsAlsoReportedAsUnavailable() {
        STUB.respondWith(500, "{\"detail\":\"boom\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isInstanceOf(OutboundServiceUnavailableException.class);
    }

    /**
     * ...but a 4xx that is not the 422 refusal is a definite answer about THIS request: booking
     * sent something inventory will never accept. Retrying later cannot help, so it must not be
     * dressed up as a temporary outage the customer is invited to wait out.
     */
    @Test
    void aMalformedRequestIsNotDressedUpAsAnOutage() {
        STUB.respondWith(400, "{\"detail\":\"seatCodes must not be empty\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.reserveSeats("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isNotInstanceOf(OutboundServiceUnavailableException.class);
    }

    // ------------------------------------------------------------------
    // confirmReservation — the call that runs AFTER the money has been taken.
    //
    // Everything above this line exercises reserveSeats or releaseSeats. confirmReservation had no
    // coverage here at all, and it is the one whose answers a customer cannot be asked to retry:
    // by the time it runs the booking is already CONFIRMED and the confirmation email is already
    // on its way.
    // ------------------------------------------------------------------

    /**
     * 422 at confirm time is {@code SeatAlreadySoldException} — the seat is SOLD to a DIFFERENT
     * booking, which happens when this booking's Redis hold lapsed (TTL 10 minutes) between
     * reserve and a confirm that had been failing, and another customer bought the seat in the
     * gap. That answer can never change, however many times it is asked.
     *
     * <p>It used to arrive at the saga as {@code new RuntimeException("Ticket inventory service
     * unavailable for confirmReservation")} — the same object, with the same words, as a 503 from
     * a service that was merely restarting. {@code BookingOrchestrationService} cannot tell them
     * apart, so it leaves {@code inventoryConfirmed} false for both and
     * {@code InventoryConfirmationReconciler} re-asks every five minutes forever.
     */
    @Test
    void aSeatSoldToAnotherBookingIsNotReportedAsAnOutage() {
        STUB.respondWith(422, "{\"detail\":\"Seat A1 already sold to booking other-booking, "
                + "cannot confirm for booking booking-1\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.confirmReservation("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isNotInstanceOf(OutboundServiceUnavailableException.class);
        assertThat(thrown.getClass()).isNotEqualTo(RuntimeException.class);
    }

    /** ...and it has to keep naming the booking that actually owns the seat, for whoever unpicks it. */
    @Test
    void aSeatSoldToAnotherBookingKeepsSayingWhichBookingOwnsIt() {
        STUB.respondWith(422, "{\"detail\":\"Seat A1 already sold to booking other-booking, "
                + "cannot confirm for booking booking-1\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.confirmReservation("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).hasMessageContaining("already sold to booking other-booking");
    }

    /**
     * Green before and after: 422 is an {@code HttpClientErrorException} on the retry ignore list
     * today, and must stay un-retried once it is converted to a type of its own — a conversion
     * that happens in the method body, so the new type has to be on that list too or the answer
     * gets asked for three times.
     */
    @Test
    void aRefusedSaleIsAskedForOnceOnly() {
        STUB.respondWith(422, "{\"detail\":\"Seat A1 already sold to booking other-booking, "
                + "cannot confirm for booking booking-1\"}");

        catchThrowable(() -> ticketInventoryPort.confirmReservation("showtime-1", "booking-1", List.of("A1")));

        assertThat(STUB.requestCount()).isEqualTo(1);
    }

    /**
     * The escalation, and the reason this refusal has to be converted inside the method body the
     * way reserveSeats converts its own rather than in the fallback. The fallback runs OUTSIDE the
     * circuit-breaker aspect, so whatever it throws is invisible to the breaker; what the breaker
     * records is the raw {@code HttpClientErrorException}, and 422 is not on its ignore list.
     *
     * <p>{@code InventoryConfirmationReconciler} re-asks every stuck booking every five minutes,
     * so a handful of permanently-refused bookings is a standing supply of breaker failures. Five
     * of them are enough (minimumNumberOfCalls=5, failureRateThreshold=50) — and an open
     * {@code ticket-inventory} breaker takes reserveSeats down with it, which is the whole buy
     * path, for bookings that are already lost. Exactly the shape
     * {@link #seatContentionDoesNotOpenTheCircuitOnAServiceThatAnsweredCorrectly} fixed on the
     * reserve side, in the one place it was not fixed.
     */
    @Test
    void aRefusedSaleDoesNotOpenTheCircuitOnAServiceThatAnsweredCorrectly() {
        STUB.respondWith(422, "{\"detail\":\"Seat A1 already sold to booking other-booking, "
                + "cannot confirm for booking booking-1\"}");

        for (int i = 0; i < 6; i++) {
            catchThrowable(() -> ticketInventoryPort.confirmReservation("showtime-1", "booking-1", List.of("A1")));
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * 404 at confirm time is {@code SeatInventoryNotFoundException}: the showtime has no seat
     * inventory at all. Like the 422 it is a definite answer about these seats from a service that
     * is working, so it must not be retried forever as though the service were down. Same pair as
     * {@link #aShowtimeWithNoInventoryIsARefusalNotAFailure} on the reserve side.
     */
    @Test
    void aShowtimeWithNoInventoryAtConfirmTimeIsARefusalNotAnOutage() {
        STUB.respondWith(404, "{\"detail\":\"SeatInventory not found for showtime: showtime-1\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.confirmReservation("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isNotInstanceOf(OutboundServiceUnavailableException.class);
        assertThat(thrown.getClass()).isNotEqualTo(RuntimeException.class);
    }

    /**
     * The other half. A 4xx that is NOT one of those two — a 403 because the internal service
     * token was misconfigured, a 400 because the request shape drifted — must stay retryable: it
     * is fixable by an operator without abandoning a booking the customer has already paid for.
     * Only "these seats are not yours" is permanent.
     */
    @Test
    void aMisconfiguredConfirmIsStillTreatedAsSomethingThatMightRecover() {
        STUB.respondWith(403, "{\"detail\":\"This operation is restricted to internal service calls\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.confirmReservation("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isInstanceOf(OutboundServiceUnavailableException.class);
    }

    /**
     * And an unreachable inventory keeps the name the saga can act on, instead of the bare
     * {@code RuntimeException} that made every failure of this call look identical — the same
     * correction {@link #anUnreachableInventoryIsReportedAsUnavailableNotAsABareRuntimeException}
     * made for reserveSeats.
     */
    @Test
    void anUnreachableInventoryAtConfirmTimeIsReportedAsUnavailable() {
        STUB.respondWith(500, "{\"detail\":\"boom\"}");

        Throwable thrown = catchThrowable(
                () -> ticketInventoryPort.confirmReservation("showtime-1", "booking-1", List.of("A1")));

        assertThat(thrown).isInstanceOf(OutboundServiceUnavailableException.class);
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
