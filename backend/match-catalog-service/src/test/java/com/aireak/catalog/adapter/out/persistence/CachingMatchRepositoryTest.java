package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.adapter.out.cache.RedisLiveSeatStore;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.catalog.domain.model.Showtime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link CachingMatchRepository} against a real Redis (Testcontainers) — the local +
 * Redis two-tier read-through, save()-triggered invalidation, and live-seat overlay all hinge on
 * real Redisson/Guava behavior, not something worth mocking. {@link MatchPersistenceAdapter} (the
 * JPA-backed delegate) is mocked — its own correctness is covered elsewhere; here it stands in for
 * "the DB" so cache hits/misses can be asserted precisely.
 */
@Testcontainers
@ExtendWith(MockitoExtension.class)
class CachingMatchRepositoryTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static RedissonClient redissonClient;

    @Mock
    private MatchPersistenceAdapter delegate;

    private RedisLiveSeatStore liveSeatStore;
    private CachingMatchRepository repository;

    @BeforeAll
    static void setUpRedis() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void tearDownRedis() {
        redissonClient.shutdown();
    }

    @BeforeEach
    void setUp() {
        // Flush between tests so cache state from one test never leaks into the next via the
        // shared Redis container.
        redissonClient.getKeys().flushdb();
        liveSeatStore = new RedisLiveSeatStore(redissonClient);
        repository = new CachingMatchRepository(delegate, liveSeatStore, redissonClient);
    }

    private static Match aPublishedMatch(String matchId, String showtimeId, int availableSeats) {
        Showtime showtime = new Showtime(showtimeId, Instant.parse("2026-09-01T18:00:00Z"), "stadium-1",
                100, availableSeats, new BigDecimal("50.00"), "USD");
        return Match.reconstitute(matchId, "Home FC", "Away FC", "V.League 1",
                MatchStatus.PUBLISHED, Instant.now(), List.of(showtime));
    }

    @Test
    void findByIdOnAFreshMatchIdLoadsFromDelegateAndPopulatesBothCacheTiers() {
        Match match = aPublishedMatch("match-1", "showtime-1", 100);
        when(delegate.findById("match-1")).thenReturn(Optional.of(match));

        Optional<Match> result = repository.findById("match-1");

        assertThat(result).isPresent();
        assertThat(result.get().getMatchId()).isEqualTo("match-1");
        verify(delegate, times(1)).findById("match-1");
    }

    @Test
    void secondFindByIdIsAnsweredFromLocalCacheWithoutTouchingDelegateAgain() {
        Match match = aPublishedMatch("match-1", "showtime-1", 100);
        when(delegate.findById("match-1")).thenReturn(Optional.of(match));

        repository.findById("match-1");
        repository.findById("match-1");

        verify(delegate, times(1)).findById("match-1");
    }

    @Test
    void aSeparateInstanceSharingRedisServesFromRedisWithoutTouchingItsOwnDelegate() {
        Match match = aPublishedMatch("match-1", "showtime-1", 100);
        when(delegate.findById("match-1")).thenReturn(Optional.of(match));
        repository.findById("match-1"); // populates Redis via this instance

        // Simulates a different pod: its own local cache is empty, but shares the same Redis.
        MatchPersistenceAdapter otherDelegate = org.mockito.Mockito.mock(MatchPersistenceAdapter.class);
        CachingMatchRepository otherPod = new CachingMatchRepository(otherDelegate, liveSeatStore, redissonClient);

        Optional<Match> result = otherPod.findById("match-1");

        assertThat(result).isPresent();
        assertThat(result.get().getMatchId()).isEqualTo("match-1");
        verify(otherDelegate, never()).findById(any());
    }

    @Test
    void saveInvalidatesTheCacheSoASubsequentFindByIdHitsTheDelegateAgain() {
        Match match = aPublishedMatch("match-1", "showtime-1", 100);
        when(delegate.findById("match-1")).thenReturn(Optional.of(match));
        repository.findById("match-1");

        repository.save(match);
        repository.findById("match-1");

        verify(delegate, times(2)).findById("match-1");
        verify(delegate).save(match);
    }

    @Test
    void findByIdAlwaysOverlaysTheLatestLiveSeatCountEvenWhenTheCachedMatchIsStale() {
        Match match = aPublishedMatch("match-1", "showtime-1", 100);
        when(delegate.findById("match-1")).thenReturn(Optional.of(match));
        repository.findById("match-1"); // caches availableSeats=100

        liveSeatStore.publish("showtime-1", 37); // a ticket sale happens, write-through to Redis

        Optional<Match> result = repository.findById("match-1"); // still a cache hit (not invalidated)

        assertThat(result).isPresent();
        assertThat(result.get().getShowtimes().get(0).getAvailableSeats()).isEqualTo(37);
        verify(delegate, times(1)).findById("match-1"); // overlay didn't require a fresh DB load
    }

    @Test
    void findByStatusCachesTheIdListSoASecondPageRequestSkipsTheFullScan() {
        Match match1 = aPublishedMatch("match-1", "showtime-1", 100);
        Match match2 = aPublishedMatch("match-2", "showtime-2", 50);
        when(delegate.findByStatus(eq(MatchStatus.PUBLISHED), eq(0), anyInt()))
                .thenReturn(List.of(match1, match2));
        when(delegate.findById("match-1")).thenReturn(Optional.of(match1));
        when(delegate.findById("match-2")).thenReturn(Optional.of(match2));

        List<Match> firstPage = repository.findByStatus(MatchStatus.PUBLISHED, 0, 20);
        long total = repository.countByStatus(MatchStatus.PUBLISHED);
        List<Match> secondCall = repository.findByStatus(MatchStatus.PUBLISHED, 0, 20);

        assertThat(firstPage).hasSize(2);
        assertThat(total).isEqualTo(2);
        assertThat(secondCall).hasSize(2);
        verify(delegate, times(1)).findByStatus(eq(MatchStatus.PUBLISHED), eq(0), anyInt());
    }

    @Test
    void saveInvalidatesTheIdListCacheSoANewMatchAppearsWithoutWaitingForTtl() {
        Match match1 = aPublishedMatch("match-1", "showtime-1", 100);
        when(delegate.findByStatus(eq(MatchStatus.PUBLISHED), eq(0), anyInt()))
                .thenReturn(List.of(match1))
                .thenReturn(List.of(match1, aPublishedMatch("match-2", "showtime-2", 50)));
        when(delegate.findById("match-1")).thenReturn(Optional.of(match1));
        when(delegate.findById("match-2")).thenReturn(Optional.of(aPublishedMatch("match-2", "showtime-2", 50)));

        repository.findByStatus(MatchStatus.PUBLISHED, 0, 20);
        repository.save(match1); // e.g. a newly-published match, unconditional invalidation
        List<Match> afterInvalidation = repository.findByStatus(MatchStatus.PUBLISHED, 0, 20);

        assertThat(afterInvalidation).hasSize(2);
        verify(delegate, times(2)).findByStatus(eq(MatchStatus.PUBLISHED), eq(0), anyInt());
    }
}
