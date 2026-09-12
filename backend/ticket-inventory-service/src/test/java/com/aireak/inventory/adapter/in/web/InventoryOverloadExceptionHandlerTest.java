package com.aireak.inventory.adapter.in.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aireak.common.web.advice.GlobalExceptionHandler;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryOverloadExceptionHandlerTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AtomicLong nanoTime = new AtomicLong();
    private final InventoryOverloadExceptionHandler handler =
            new InventoryOverloadExceptionHandler(meterRegistry, nanoTime::get);

    private ListAppender<ILoggingEvent> logged;
    private Logger handlerLogger;

    @BeforeEach
    void captureLogging() {
        logged = new ListAppender<>();
        logged.start();
        handlerLogger = (Logger) LoggerFactory.getLogger(InventoryOverloadExceptionHandler.class);
        handlerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLogging() {
        handlerLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void bulkheadRejectionBecomes503WithRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleBulkheadFull(bulkheadFull());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getType())
                .hasToString("https://aireak.com/errors/bulkhead-full");
    }

    @Test
    void rateLimiterRejectionBecomes503WithRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimited(rateLimited());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getType())
                .hasToString("https://aireak.com/errors/rate-limited");
    }

    /**
     * The counter is the exact record of what was shed; the log is only a heartbeat. So every
     * rejection must land on the counter, under its own reason, whether or not it was logged.
     */
    @Test
    void everyRejectionIsCountedUnderItsReason() {
        handler.handleBulkheadFull(bulkheadFull());
        handler.handleBulkheadFull(bulkheadFull());
        handler.handleBulkheadFull(bulkheadFull());
        handler.handleRateLimited(rateLimited());

        assertThat(rejected("bulkhead-full")).isEqualTo(3.0);
        assertThat(rejected("rate-limited")).isEqualTo(1.0);
    }

    /**
     * A flood of rejections inside one second must produce ONE log line, not one per request:
     * the per-request WARN, written through two synchronous appenders, was what made shedding
     * cost as much as serving. The line written once the interval has passed carries everything
     * shed in between, so nothing is lost from the log either, only batched.
     */
    @Test
    void logsAtMostOnceASecondPerReasonAndCarriesTheCountSinceLastLine() {
        for (int i = 0; i < 500; i++) {
            handler.handleRateLimited(rateLimited());
        }
        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logged.list.get(0).getFormattedMessage())
                .startsWith("Rate limit exceeded: 1 request(s) rejected since last report");

        nanoTime.addAndGet(InventoryOverloadExceptionHandler.LOG_INTERVAL.toNanos() - 1);
        handler.handleRateLimited(rateLimited());
        assertThat(logged.list).as("still inside the interval").hasSize(1);

        nanoTime.addAndGet(1);
        handler.handleRateLimited(rateLimited());
        assertThat(logged.list).hasSize(2);
        assertThat(logged.list.get(1).getFormattedMessage())
                .startsWith("Rate limit exceeded: 501 request(s) rejected since last report");

        assertThat(rejected("rate-limited")).as("the counter never samples").isEqualTo(502.0);
    }

    /** Each reason samples on its own clock: a bulkhead line is not suppressed by a rate-limit line. */
    @Test
    void logSamplingIsPerReason() {
        handler.handleRateLimited(rateLimited());
        handler.handleBulkheadFull(bulkheadFull());

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(m -> assertThat(m).startsWith("Rate limit exceeded: 1"))
                .anySatisfy(m -> assertThat(m).startsWith("Bulkhead full: 1"))
                .hasSize(2);
    }

    /**
     * The tests above call the handler methods directly, which proves what they return but not
     * that Spring ever calls them. Spring takes the FIRST {@code @ControllerAdvice} in order that has
     * a handler for the thrown type, and {@code GlobalExceptionHandler} has one for
     * {@link Exception} — so if it is asked first, a bulkhead rejection comes back as its generic
     * 500 and this class is never consulted. Registering it first here is the adverse order on
     * purpose: only {@code @Order} makes the assertion below hold.
     */
    @Test
    void bulkheadRejectionStillBecomes503WhenGlobalExceptionHandlerIsRegisteredFirst() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new OverloadedController())
                .setControllerAdvice(new GlobalExceptionHandler(), handler)
                .build();

        mockMvc.perform(get("/overloaded"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
    }

    private double rejected(String reason) {
        return meterRegistry.counter(InventoryOverloadExceptionHandler.REJECTED_METRIC, "reason", reason).count();
    }

    private static BulkheadFullException bulkheadFull() {
        return BulkheadFullException.createBulkheadFullException(
                Bulkhead.of("seat-inventory", BulkheadConfig.custom()
                        .maxConcurrentCalls(1)
                        .maxWaitDuration(Duration.ZERO)
                        .build()));
    }

    private static RequestNotPermitted rateLimited() {
        return RequestNotPermitted.createRequestNotPermitted(
                RateLimiter.of("seat-inventory", RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build()));
    }

    @RestController
    static class OverloadedController {
        @GetMapping("/overloaded")
        String reserve() {
            throw bulkheadFull();
        }
    }
}
