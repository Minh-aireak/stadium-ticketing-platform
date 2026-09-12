package com.aireak.inventory.adapter.in.web;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Maps Resilience4j's Bulkhead/RateLimiter rejections on the reserve/release use cases to
 * 503 Service Unavailable with a Retry-After header, instead of falling through to
 * {@code GlobalExceptionHandler}'s generic 500 — these are deliberate fail-fast rejections
 * under load, not unexpected server errors.
 *
 * <p>{@code @Order} is what actually makes that happen, and is not decoration. Spring asks each
 * {@code @ControllerAdvice} in order and takes the first one that has a handler for the exception;
 * {@code GlobalExceptionHandler} has {@code @ExceptionHandler(Exception.class)}, so it has a
 * handler for every exception there is. With both advices unordered they both sat at
 * {@code LOWEST_PRECEDENCE} and the winner was decided by bean-definition order — which comes from
 * the order the two packages happen to be listed in {@code @SpringBootApplication(scanBasePackages)}.
 * Listing {@code com.aireak.common} first there, an edit with no visible connection to this class,
 * turned every load-shed rejection into a 500 logged as "Unexpected error".
 *
 * <p><strong>Rejections are counted, not logged one by one.</strong> Same construction, and same
 * reason, as match-catalog-service's {@code CatalogOverloadExceptionHandler}: {@code seat-inventory}
 * has {@code timeoutDuration: 0} and {@code maxWaitDuration: 0} so that a flood is shed in
 * microseconds instead of queued, and a WARN per rejection through two synchronous appenders put
 * thousands of contended file writes per second on exactly that path. On catalog that log alone
 * was measured at 7–18% of the rate the service could reject. This service has not been measured
 * separately — its limiter is tighter (200/s and 100 in flight per instance) and it sits on the
 * buy path, so the share of a flash sale it sheds is larger, not smaller. Each rejection now
 * increments {@code inventory_seat_rejected_total{reason}} and the log gets at most one line per
 * second per reason, carrying the number of rejections since the previous line. The counter is
 * exact; the log line is a heartbeat. The tail of a burst — rejections after the last line inside
 * its final second — is only reported on the next line, and not at all if the flood stops there.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class InventoryOverloadExceptionHandler {

    /**
     * Prometheus: {@code inventory_seat_rejected_total{reason="bulkhead-full"|"rate-limited"}}.
     * "seat", not "reserve": the {@code seat-inventory} bulkhead guards hold, unhold, reserve and
     * release alike, and this handler cannot tell which one was shed.
     */
    static final String REJECTED_METRIC = "inventory.seat.rejected";
    static final Duration LOG_INTERVAL = Duration.ofSeconds(1);

    private static final String TYPE_BASE = "https://aireak.com/errors/";
    private static final String RETRY_AFTER_SECONDS = "1";

    private final Rejections bulkheadFull;
    private final Rejections rateLimited;

    @Autowired
    public InventoryOverloadExceptionHandler(MeterRegistry meterRegistry) {
        this(meterRegistry, System::nanoTime);
    }

    /** {@code nanoTime} is injectable so a test can move the log-sampling clock deterministically. */
    InventoryOverloadExceptionHandler(MeterRegistry meterRegistry, LongSupplier nanoTime) {
        this.bulkheadFull = new Rejections("bulkhead-full", "Bulkhead full", meterRegistry, nanoTime);
        this.rateLimited = new Rejections("rate-limited", "Rate limit exceeded", meterRegistry, nanoTime);
    }

    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ProblemDetail> handleBulkheadFull(BulkheadFullException ex) {
        bulkheadFull.record(ex);
        return overloadResponse("bulkhead-full", "Seat inventory service is at capacity");
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RequestNotPermitted ex) {
        rateLimited.record(ex);
        return overloadResponse("rate-limited", "Too many concurrent reservation attempts");
    }

    private ResponseEntity<ProblemDetail> overloadResponse(String typeSuffix, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problem.setType(URI.create(TYPE_BASE + typeSuffix));
        problem.setTitle("Service Overloaded");
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }

    /**
     * One reason's counter plus its sampled log. Lock-free on purpose — this runs once per shed
     * request, concurrently across every Tomcat thread — and the two atomics are not updated
     * together atomically: a rejection that lands between {@code pending.incrementAndGet()} and a
     * concurrent thread's {@code pending.getAndSet(0)} is reported on that thread's line rather
     * than the next one. Every rejection is reported exactly once either way; only which line
     * carries it is racy, and that is fine for a heartbeat.
     */
    private static final class Rejections {

        private final String what;
        private final Counter counter;
        private final LongSupplier nanoTime;
        private final AtomicLong pending = new AtomicLong();
        /** {@code nanoTime} value from which the next log line is allowed; compared by difference so wraparound is harmless. */
        private final AtomicLong nextLogAt;

        Rejections(String reason, String what, MeterRegistry meterRegistry, LongSupplier nanoTime) {
            this.what = what;
            this.counter = meterRegistry.counter(REJECTED_METRIC, "reason", reason);
            this.nanoTime = nanoTime;
            this.nextLogAt = new AtomicLong(nanoTime.getAsLong());
        }

        void record(Exception ex) {
            counter.increment();
            pending.incrementAndGet();
            long now = nanoTime.getAsLong();
            long next = nextLogAt.get();
            if (now - next >= 0 && nextLogAt.compareAndSet(next, now + LOG_INTERVAL.toNanos())) {
                log.warn("{}: {} request(s) rejected since last report — {}", what, pending.getAndSet(0), ex.getMessage());
            }
        }
    }
}
