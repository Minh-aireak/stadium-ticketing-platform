package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.application.port.out.LiveSeatAvailabilityPort;
import com.aireak.catalog.application.port.out.MatchRepository;
import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;
import com.aireak.catalog.domain.model.Showtime;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Two-tier (local Guava, then Redis) read cache in front of {@link MatchPersistenceAdapter} for
 * match-catalog-service's public browse endpoints (GET /matches with no {@code ?q=}, and
 * GET /matches/{id}) — the Elasticsearch search path is untouched.
 *
 * <p>{@code availableSeats} is never trusted from either cache tier: it changes continuously via
 * {@code SeatsSoldEventConsumer}, out of band from any write this class knows about. Every Match
 * returned here has its showtimes' seat counts overlaid from {@link LiveSeatAvailabilityPort}
 * right before returning (falling back to whatever the cached/loaded Match already carried on a
 * miss there) — see {@link #overlayLiveSeats}.
 *
 * <p>Local TTL is the practical bound on how stale an admin write (publish/cancel/complete) can
 * look on a DIFFERENT pod — {@link #save} actively invalidates Redis, but there is no way to reach
 * into another pod's local cache. Same trade-off booking-service's {@code RedissonIdempotencyStore}
 * accepts for its own local tier.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class CachingMatchRepository implements MatchRepository {

    private static final String MATCH_KEY_PREFIX = "catalog:match:";
    private static final String STATUS_IDS_KEY_PREFIX = "catalog:matches:ids:";

    private static final long LOCAL_CACHE_MAX_SIZE = 1_000;
    private static final Duration LOCAL_CACHE_TTL = Duration.ofSeconds(5);
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);

    // Hibernate already loads every match of a given status before paginating in Java (see
    // MatchJpaRepository#findByStatusWithShowtimes javadoc) — asking for this many just reuses
    // that existing full load instead of adding a second, unpaginated repository method.
    private static final int FULL_SCAN_SIZE = 100_000;

    private final Cache<String, Match> localMatchCache = CacheBuilder.newBuilder()
            .maximumSize(LOCAL_CACHE_MAX_SIZE).expireAfterWrite(LOCAL_CACHE_TTL).build();
    // Bounded by MatchStatus's tiny value set — one entry per status, not per page/size.
    private final Cache<String, List<String>> localIdListCache = CacheBuilder.newBuilder()
            .maximumSize(16).expireAfterWrite(LOCAL_CACHE_TTL).build();

    private final MatchPersistenceAdapter delegate;
    private final LiveSeatAvailabilityPort liveSeatAvailabilityPort;
    private final RedissonClient redissonClient;

    @Override
    public void save(Match match) {
        delegate.save(match);
        invalidate(match.getMatchId());
    }

    @Override
    public Optional<Match> findById(String matchId) {
        Match match = localMatchCache.getIfPresent(matchId);
        if (match == null) {
            match = redisGetMatch(matchId).orElse(null);
            if (match == null) {
                Optional<Match> loaded = delegate.findById(matchId);
                if (loaded.isEmpty()) {
                    return Optional.empty();
                }
                match = loaded.get();
                redisPutMatch(match);
            }
            localMatchCache.put(matchId, match);
        }
        return Optional.of(overlayLiveSeats(match));
    }

    @Override
    public Optional<Match> findByShowtimeId(String showtimeId) {
        // Not part of the cached surface (used by getShowtime, out of scope) — pass through.
        return delegate.findByShowtimeId(showtimeId);
    }

    @Override
    public List<Match> findByStatus(MatchStatus status, int page, int size) {
        List<String> ids = resolveIdList(status);
        int from = Math.min(page * size, ids.size());
        int to = Math.min(from + size, ids.size());
        // Each id is resolved via findById above, so a page render only pays for exactly the
        // matches it displays — never for the whole status set on a cold id-list cache.
        return ids.subList(from, to).stream()
                .map(this::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public long countByStatus(MatchStatus status) {
        return resolveIdList(status).size();
    }

    @Override
    public boolean existsShowtimeAtVenueAndTime(String venueId, Instant startTime) {
        // Write-path validation (addShowtime), not part of the cached read surface — pass through.
        return delegate.existsShowtimeAtVenueAndTime(venueId, startTime);
    }

    // ----------------------------------------------------------------
    // Cache internals
    // ----------------------------------------------------------------

    private List<String> resolveIdList(MatchStatus status) {
        String key = STATUS_IDS_KEY_PREFIX + status;
        List<String> ids = localIdListCache.getIfPresent(key);
        if (ids != null) {
            return ids;
        }
        ids = redisGetIdList(key).orElse(null);
        if (ids == null) {
            ids = delegate.findByStatus(status, 0, FULL_SCAN_SIZE).stream().map(Match::getMatchId).toList();
            redisPutIdList(key, ids);
        }
        localIdListCache.put(key, ids);
        return ids;
    }

    private Match overlayLiveSeats(Match match) {
        List<Showtime> refreshed = match.getShowtimes().stream()
                .map(s -> liveSeatAvailabilityPort.get(s.getShowtimeId())
                        .map(seats -> new Showtime(s.getShowtimeId(), s.getStartTime(), s.getVenueId(),
                                s.getTotalSeats(), seats, s.getBasePrice(), s.getCurrency()))
                        .orElse(s))
                .toList();
        return Match.reconstitute(match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(),
                match.getCompetition(), match.getStatus(), match.getCreatedAt(), refreshed);
    }

    // Unconditional on every save() (create/addShowtime/publish/cancel/complete alike) rather than
    // trying to infer which status-list(s) a given write could have affected — save() is a
    // low-frequency admin action, so invalidating all of MatchStatus's handful of id-list entries
    // plus the one match entry costs nothing and can't miss a case.
    private void invalidate(String matchId) {
        localMatchCache.invalidate(matchId);
        try {
            redissonClient.getBucket(MATCH_KEY_PREFIX + matchId).delete();
        } catch (Exception e) {
            log.warn("Redis invalidation failed for match cache: matchId={}, error={}", matchId, e.getMessage());
        }
        for (MatchStatus status : MatchStatus.values()) {
            String key = STATUS_IDS_KEY_PREFIX + status;
            localIdListCache.invalidate(key);
            try {
                redissonClient.getBucket(key).delete();
            } catch (Exception e) {
                log.warn("Redis invalidation failed for status id-list cache: status={}, error={}", status, e.getMessage());
            }
        }
    }

    private Optional<Match> redisGetMatch(String matchId) {
        try {
            RBucket<Match> bucket = redissonClient.getBucket(MATCH_KEY_PREFIX + matchId);
            return Optional.ofNullable(bucket.get());
        } catch (Exception e) {
            log.warn("Redis read failed for match cache, falling back to DB: matchId={}, error={}", matchId, e.getMessage());
            return Optional.empty();
        }
    }

    private void redisPutMatch(Match match) {
        try {
            RBucket<Match> bucket = redissonClient.getBucket(MATCH_KEY_PREFIX + match.getMatchId());
            bucket.set(match, REDIS_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for match cache: matchId={}, error={}", match.getMatchId(), e.getMessage());
        }
    }

    private Optional<List<String>> redisGetIdList(String key) {
        try {
            RBucket<List<String>> bucket = redissonClient.getBucket(key);
            return Optional.ofNullable(bucket.get());
        } catch (Exception e) {
            log.warn("Redis read failed for id-list cache, falling back to DB: key={}, error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void redisPutIdList(String key, List<String> ids) {
        try {
            RBucket<List<String>> bucket = redissonClient.getBucket(key);
            bucket.set(ids, REDIS_TTL);
        } catch (Exception e) {
            log.warn("Redis write failed for id-list cache: key={}, error={}", key, e.getMessage());
        }
    }
}
