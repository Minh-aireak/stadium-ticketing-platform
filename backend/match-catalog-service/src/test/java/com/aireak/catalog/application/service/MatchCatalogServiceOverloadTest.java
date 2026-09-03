package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.GetShowtimeUseCase;
import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.catalog.domain.model.Showtime;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.ratelimiter.autoconfigure.RateLimiterAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the {@code catalog-read} bulkhead actually wraps the public browse/search use cases and
 * only those — the admin write path must stay reachable while browse is being shed, or a flash
 * sale would also lock the operators out of the catalog they are trying to fix.
 *
 * <p>Runs against a minimal context holding just Resilience4j's aspects and the service under
 * test: the annotations only do anything through a proxy, so asserting on a plain {@code new
 * MatchCatalogService(...)} would pass no matter where the annotations sat.
 *
 * <p>{@code getShowtime} is checked against its own {@code catalog-showtime} instance rather than
 * alongside browse. It is not a browse endpoint: it is the single lookup
 * ticket-inventory-service's {@code ShowtimeCatalogRestAdapter#requireBookable} makes in front of
 * every hold and every reserve, and it has no cache to fall back on for a positive answer. Metered
 * out of the same budget as anonymous browse, a browse flood turned every buy attempt on the
 * platform into a 503 from here, which inventory then reports to the customer as a closed booking
 * window.
 */
@SpringBootTest(classes = MatchCatalogServiceOverloadTest.OverloadTestConfig.class, properties = {
        // A single permit, so the test can hold the whole bulkhead itself without racing threads.
        "resilience4j.bulkhead.instances.catalog-read.max-concurrent-calls=1",
        "resilience4j.bulkhead.instances.catalog-read.max-wait-duration=0",
        // Deliberately far out of reach: the RateLimiter aspect wraps the Bulkhead one, so it
        // must never be what rejects here, or the test would pass for the wrong reason.
        "resilience4j.ratelimiter.instances.catalog-read.limit-for-period=1000000",
        "resilience4j.ratelimiter.instances.catalog-read.limit-refresh-period=1s",
        "resilience4j.ratelimiter.instances.catalog-read.timeout-duration=0",
        // The buy path's own budget, left wide open here so this test can prove browse exhaustion
        // does not reach it.
        "resilience4j.bulkhead.instances.catalog-showtime.max-concurrent-calls=50",
        "resilience4j.bulkhead.instances.catalog-showtime.max-wait-duration=0",
        "resilience4j.ratelimiter.instances.catalog-showtime.limit-for-period=1000000",
        "resilience4j.ratelimiter.instances.catalog-showtime.limit-refresh-period=1s",
        "resilience4j.ratelimiter.instances.catalog-showtime.timeout-duration=0"
})
class MatchCatalogServiceOverloadTest {

    @Autowired
    private ListMatchesUseCase listMatchesUseCase;
    @Autowired
    private GetMatchUseCase getMatchUseCase;
    @Autowired
    private GetShowtimeUseCase getShowtimeUseCase;
    @Autowired
    private CreateMatchUseCase createMatchUseCase;
    @Autowired
    private PublishMatchUseCase publishMatchUseCase;
    @Autowired
    private BulkheadRegistry bulkheadRegistry;
    @Autowired
    private MatchRepository matchRepository;
    @Autowired
    private MatchSearchPort matchSearchPort;
    @Autowired
    private DomainEventPublisher eventPublisher;

    private Bulkhead bulkhead;

    @BeforeEach
    void setUp() {
        // The context (and so the mocks) is shared across methods here, unlike a Mockito-only test.
        reset(matchRepository, matchSearchPort, eventPublisher);
        bulkhead = bulkheadRegistry.bulkhead("catalog-read");
    }

    @AfterEach
    void releasePermit() {
        if (bulkhead.getMetrics().getAvailableConcurrentCalls() == 0) {
            bulkhead.releasePermission();
        }
    }

    @Test
    void readUseCasesAreRejectedWhenBulkheadIsFull() {
        assertThat(bulkhead.tryAcquirePermission()).isTrue();

        assertThatThrownBy(() -> listMatchesUseCase.listMatches(null, 0, 20))
                .isInstanceOf(BulkheadFullException.class);
        assertThatThrownBy(() -> getMatchUseCase.getMatch("match-1"))
                .isInstanceOf(BulkheadFullException.class);

        // Rejected before the call, not after: nothing downstream was touched.
        verify(matchRepository, org.mockito.Mockito.never()).findById(any());
    }

    /**
     * The showtime lookup is what ticket-inventory-service calls before every hold and every
     * reserve, so shedding it sheds the whole buy path. This assertion used to say the opposite —
     * it listed {@code getShowtime} among the endpoints a full browse bulkhead rejects, pinning
     * the coupling as intended behavior.
     */
    @Test
    void theShowtimeLookupThatGatesBookingSurvivesABrowseFlood() {
        assertThat(bulkhead.tryAcquirePermission()).isTrue();

        assertThatCode(() -> getShowtimeUseCase.getShowtime("showtime-1"))
                .doesNotThrowAnyException();

        verify(matchRepository).findByShowtimeId("showtime-1");
    }

    @Test
    void theShowtimeLookupIsStillShedWhenItsOwnBudgetIsExhausted() {
        Bulkhead showtimeBulkhead = bulkheadRegistry.bulkhead("catalog-showtime");
        for (int i = 0; i < 50; i++) {
            assertThat(showtimeBulkhead.tryAcquirePermission()).isTrue();
        }
        try {
            assertThatThrownBy(() -> getShowtimeUseCase.getShowtime("showtime-1"))
                    .isInstanceOf(BulkheadFullException.class);
        } finally {
            for (int i = 0; i < 50; i++) {
                showtimeBulkhead.releasePermission();
            }
        }
    }

    @Test
    void readUseCasesPassThroughWhenBulkheadHasRoom() {
        when(matchRepository.findByStatus(MatchStatus.PUBLISHED, 0, 20)).thenReturn(List.of());
        when(matchRepository.countByStatus(MatchStatus.PUBLISHED)).thenReturn(0L);

        assertThat(listMatchesUseCase.listMatches(null, 0, 20).totalElements()).isZero();
        // The permit is handed back on the way out, or the next request would be shed for free.
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void adminWriteUseCasesAreUnaffectedWhenBulkheadIsFull() {
        assertThat(bulkhead.tryAcquirePermission()).isTrue();

        Match draft = Match.reconstitute("match-1", "Home FC", "Away FC", "Premier League",
                MatchStatus.DRAFT, Instant.now(),
                List.of(new Showtime("showtime-1", Instant.now().plusSeconds(3600), "venue-1",
                        100, 100, new BigDecimal("150000"), "VND")));
        when(matchRepository.findByIdForUpdate("match-1")).thenReturn(Optional.of(draft));

        assertThatCode(() -> createMatchUseCase.createMatch("Home FC", "Away FC", "Premier League"))
                .doesNotThrowAnyException();
        assertThatCode(() -> publishMatchUseCase.publishMatch("match-1"))
                .doesNotThrowAnyException();
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({AopAutoConfiguration.class, BulkheadAutoConfiguration.class,
            RateLimiterAutoConfiguration.class})
    static class OverloadTestConfig {

        @Bean
        MatchRepository matchRepository() {
            return mock(MatchRepository.class);
        }

        @Bean
        MatchSearchPort matchSearchPort() {
            return mock(MatchSearchPort.class);
        }

        @Bean
        DomainEventPublisher domainEventPublisher() {
            return mock(DomainEventPublisher.class);
        }

        @Bean
        MatchSearchIndexer matchSearchIndexer() {
            return mock(MatchSearchIndexer.class);
        }

        @Bean
        ShowtimeSeatCounterInitializer showtimeSeatCounterInitializer() {
            return mock(ShowtimeSeatCounterInitializer.class);
        }

        @Bean
        MatchCatalogService matchCatalogService(MatchRepository matchRepository,
                                                MatchSearchPort matchSearchPort,
                                                DomainEventPublisher eventPublisher,
                                                MatchSearchIndexer matchSearchIndexer,
                                                ShowtimeSeatCounterInitializer showtimeSeatCounterInitializer) {
            return new MatchCatalogService(matchRepository, matchSearchPort, eventPublisher, matchSearchIndexer,
                    showtimeSeatCounterInitializer);
        }
    }
}
