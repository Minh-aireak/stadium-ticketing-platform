package com.aireak.inventory.adapter.out.client;

import com.aireak.common.cache.LocalStringCache;
import com.aireak.inventory.application.port.out.ShowtimeCatalogPort;
import com.aireak.inventory.domain.exception.ShowtimeBookingClosedException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * REST-backed {@link ShowtimeCatalogPort}, fronted by two local (per-JVM, NOT shared across pods)
 * caches fed by catalog-service's Kafka events rather than by caching the REST response itself:
 *
 * <ul>
 *   <li>{@link #unbookableShowtimes} — showtimeIds whose match is known CANCELLED/COMPLETED
 *       (from {@code MatchCancelledEventConsumer}/{@code MatchCompletedEventConsumer}). Once
 *       true, permanently true — safe to cache.</li>
 *   <li>{@link #startTimeByShowtime} — each showtime's immutable start time (from
 *       {@code ShowtimeAddedEventConsumer}), checked against the live clock on every call rather
 *       than cached as a boolean — so "already started" is always evaluated fresh, never stale.</li>
 * </ul>
 *
 * <p><strong>Deliberately NOT caching {@code bookable=true} itself</strong>: unlike the
 * idempotency-style caches elsewhere in this codebase (see {@code LocalStringCache}'s javadoc),
 * "bookable" is not an immutable fact — a match can be cancelled at any time and a showtime's
 * bookability decays the instant its start time passes. Caching that boolean would risk letting a
 * reserve/hold through for an already-cancelled or already-started showtime for as long as the
 * cache entry survived. Both local caches above only ever short-circuit the REJECT path (a
 * permanent, event-confirmed "no", or a locally-computed "time has passed"); the ACCEPT path
 * always falls through to this authoritative REST call to catalog-service.
 *
 * <p>Residual risk: the window between an actual cancellation/completion in catalog-service and
 * this pod's Kafka consumer catching up (normally sub-second) — during that window a reserve/hold
 * still correctly falls through to the REST call above, which is the same behavior as before this
 * cache existed.
 */
@Component
@RequiredArgsConstructor
public class ShowtimeCatalogRestAdapter implements ShowtimeCatalogPort {

    private static final long LOCAL_CACHE_MAX_SIZE = 20_000;
    private static final Duration LOCAL_CACHE_TTL = Duration.ofDays(30);

    private final LocalStringCache unbookableShowtimes =
            new LocalStringCache(LOCAL_CACHE_MAX_SIZE, LOCAL_CACHE_TTL);
    private final LocalStringCache startTimeByShowtime =
            new LocalStringCache(LOCAL_CACHE_MAX_SIZE, LOCAL_CACHE_TTL);

    @Qualifier("catalogRestClient")
    private final RestClient restClient;

    @Value("${services.catalog.base-url:http://localhost:8082}")
    private String baseUrl;

    @Override
    public void requireBookable(String showtimeId) {
        if (unbookableShowtimes.getIfPresent(showtimeId).isPresent()) {
            throw new ShowtimeBookingClosedException(showtimeId);
        }
        Optional<String> cachedStartTime = startTimeByShowtime.getIfPresent(showtimeId);
        if (cachedStartTime.isPresent() && !Instant.parse(cachedStartTime.get()).isAfter(Instant.now())) {
            throw new ShowtimeBookingClosedException(showtimeId);
        }

        try {
            ShowtimeAvailability response = restClient.get()
                    .uri(baseUrl + "/api/v1/showtimes/{showtimeId}", showtimeId)
                    .retrieve()
                    .body(ShowtimeAvailability.class);
            if (response == null || !response.bookable()
                    || response.startTime() == null || !response.startTime().isAfter(Instant.now())) {
                throw new ShowtimeBookingClosedException(showtimeId);
            }
        } catch (ShowtimeBookingClosedException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ShowtimeBookingClosedException(showtimeId, exception);
        }
    }

    @Override
    public void markUnbookable(List<String> showtimeIds) {
        showtimeIds.forEach(showtimeId -> unbookableShowtimes.put(showtimeId, "1"));
    }

    @Override
    public void rememberStartTime(String showtimeId, Instant startTime) {
        startTimeByShowtime.put(showtimeId, startTime.toString());
    }

    record ShowtimeAvailability(String showtimeId, Instant startTime, boolean bookable) {
    }
}
