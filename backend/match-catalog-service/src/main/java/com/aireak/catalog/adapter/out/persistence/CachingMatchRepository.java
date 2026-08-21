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
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Two-tier (local Guava, then Redis) read cache in front of {@link MatchPersistenceAdapter} for
 * match-catalog-service's public read endpoints.
 *
 * <p>Every read path funnels through {@link #loadAll}, which resolves a whole batch of match ids
 * at once: local cache first, then ONE Redis MGET for the local misses, then ONE call into the
 * delegate for whatever Redis did not have, and finally ONE MGET for the live seat counts. The
 * round-trip count is therefore fixed regardless of page size — it used to scale with it, because
 * each id was resolved by its own findById and each showtime by its own Redis GET (a 20-match page
 * with 3 showtimes each cost roughly 80 sequential hops).
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
        return loadAll(List.of(matchId)).stream().findFirst();
    }

    @Override
    public List<Match> findAllByIds(Collection<String> matchIds) {
        // distinct() to match the delegate, which has to de-duplicate anyway before building its
        // IN list — without it the two implementations would disagree on a repeated id.
        return loadAll(matchIds.stream().distinct().toList());
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
        return loadAll(ids.subList(from, to));
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
    // Batch read-through
    // ----------------------------------------------------------------

    /**
     * Resolves every id through the tiers in batch, preserving the caller's order (page order, or
     * search-relevance order). Ids that exist in no tier are dropped, exactly as the old per-id
     * findById chain dropped an empty Optional.
     */
    private List<Match> loadAll(List<String> matchIds) {
        if (matchIds.isEmpty()) {
            return List.of();
        }

        Map<String, Match> resolved = LinkedHashMap.newLinkedHashMap(matchIds.size());
        List<String> localMisses = new ArrayList<>();
        for (String matchId : matchIds) {
            Match local = localMatchCache.getIfPresent(matchId);
            if (local != null) {
                resolved.put(matchId, local);
            } else {
                localMisses.add(matchId);
            }
        }

        if (!localMisses.isEmpty()) {
            Map<String, Match> fromRedis = redisGetMatches(localMisses);
            fromRedis.forEach((matchId, match) -> {
                resolved.put(matchId, match);
                localMatchCache.put(matchId, match);
            });

            List<String> redisMisses = localMisses.stream().filter(id -> !fromRedis.containsKey(id)).toList();
            if (!redisMisses.isEmpty()) {
                List<Match> loaded = delegate.findAllByIds(redisMisses);
                for (Match match : loaded) {
                    resolved.put(match.getMatchId(), match);
                    localMatchCache.put(match.getMatchId(), match);
                }
                redisPutMatches(loaded);
            }
        }

        List<Match> ordered = matchIds.stream().map(resolved::get).filter(Objects::nonNull).toList();
        return overlayLiveSeats(ordered);
    }

    private List<String> resolveIdList(MatchStatus status) {
        String key = STATUS_IDS_KEY_PREFIX + status;
        List<String> ids = localIdListCache.getIfPresent(key);
        if (ids != null) {
            return ids;
        }
        ids = redisGetIdList(key).orElse(null);
        if (ids == null) {
            // Ids only. This used to load every match of the status WITH its showtimes and then
            // keep nothing but the id — roughly 20x the bytes for the same answer.
            ids = delegate.findIdsByStatus(status);
            redisPutIdList(key, ids);
        }
        localIdListCache.put(key, ids);
        return ids;
    }

    /** One MGET for the whole batch's live seat counts, applied across every match in it. */
    private List<Match> overlayLiveSeats(List<Match> matches) {
        List<String> showtimeIds = matches.stream()
                .flatMap(match -> match.getShowtimes().stream().map(Showtime::getShowtimeId))
                .toList();
        if (showtimeIds.isEmpty()) {
            return matches;
        }
        Map<String, Integer> liveSeats = liveSeatAvailabilityPort.getAll(showtimeIds);
        if (liveSeats.isEmpty()) {
            return matches;
        }
        return matches.stream().map(match -> overlayLiveSeats(match, liveSeats)).toList();
    }

    private Match overlayLiveSeats(Match match, Map<String, Integer> liveSeats) {
        List<Showtime> refreshed = match.getShowtimes().stream()
                .map(s -> Optional.ofNullable(liveSeats.get(s.getShowtimeId()))
                        .map(seats -> new Showtime(s.getShowtimeId(), s.getStartTime(), s.getVenueId(),
                                s.getTotalSeats(), seats, s.getBasePrice(), s.getCurrency()))
                        .orElse(s))
                .toList();
        return Match.reconstitute(match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(),
                match.getCompetition(), match.getStatus(), match.getCreatedAt(), refreshed);
    }

    // ----------------------------------------------------------------
    // Cache internals
    // ----------------------------------------------------------------

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

    private Map<String, Match> redisGetMatches(List<String> matchIds) {
        String[] keys = matchIds.stream().map(id -> MATCH_KEY_PREFIX + id).toArray(String[]::new);
        try {
            Map<String, Match> raw = redissonClient.getBuckets().get(keys);
            Map<String, Match> byId = HashMap.newHashMap(raw.size());
            raw.forEach((key, match) -> {
                if (match != null) {
                    byId.put(key.substring(MATCH_KEY_PREFIX.length()), match);
                }
            });
            return byId;
        } catch (Exception e) {
            log.warn("Redis read failed for match cache, falling back to DB: count={}, error={}",
                    keys.length, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Pipelined through {@link RBatch} so N freshly-loaded matches cost one round-trip, not N.
     * {@code RBuckets.trySet} would be the natural MSET here but carries no TTL, and these entries
     * must expire.
     */
    private void redisPutMatches(Collection<Match> matches) {
        if (matches.isEmpty()) {
            return;
        }
        try {
            RBatch batch = redissonClient.createBatch();
            for (Match match : matches) {
                batch.<Match>getBucket(MATCH_KEY_PREFIX + match.getMatchId()).setAsync(match, REDIS_TTL);
            }
            batch.execute();
        } catch (Exception e) {
            log.warn("Redis write failed for match cache: count={}, error={}", matches.size(), e.getMessage());
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
