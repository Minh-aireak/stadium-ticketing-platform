package com.aireak.inventory.adapter.out.lock;

import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.domain.exception.SeatsNotAvailableException;
import com.aireak.inventory.domain.model.SeatCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redisson implementation of {@link SeatHoldPort}, backed by a single
 * {@code RMapCache} ("seat-holds") shared across all showtimes — key
 * {@code "{showtimeId}:{seatCode}"}, per-entry TTL. Value encoding depends on
 * what the hold represents:
 * <ul>
 *     <li>a standalone pre-booking hold ({@link #holdSeats}) — value is the plain owner id
 *         (a customerId)</li>
 *     <li>a hold confirmed into a booking ({@link #confirmHold}) — value is
 *         {@code "{customerId}" + OWNER_SEP + "{bookingId}"}, so the owning customer stays
 *         recoverable even though every other port method (and every Redisson-external caller)
 *         only ever refers to the hold by bookingId. This is what lets
 *         {@link #isHeldByCustomerAndBooking} verify a customer-token release actually owns the
 *         reservation instead of trusting a bookingId path variable alone.</li>
 * </ul>
 *
 * <p>Caller ({@code SeatInventoryService}) already serializes concurrent
 * attempts for the same showtime via {@code DistributedLockPort}, so a plain
 * check-then-put loop here is race-free for a single showtime; {@code
 * putIfAbsent} is still used per key as defense-in-depth against holds left
 * over from a differently-scoped caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonSeatHoldAdapter implements SeatHoldPort {

    private static final String CACHE_NAME = "seat-holds";

    // Unlikely to ever appear inside a UUID-shaped customerId/bookingId; if it ever did, the
    // owner would just fail to parse as a confirmed hold and be treated as a plain-owner value
    // (matches nothing when compared for isHeldByCustomerAndBooking) — no ambiguity, fails closed.
    private static final String OWNER_SEP = "::";

    private final RedissonClient redissonClient;

    @Value("${inventory.hold.ttl-minutes:10}")
    private long holdTtlMinutes;

    @Override
    public void holdSeats(String showtimeId, List<SeatCode> seatCodes, String bookingId) {
        RMapCache<String, String> holds = redissonClient.getMapCache(CACHE_NAME);
        List<SeatCode> placed = new ArrayList<>();
        List<SeatCode> unavailable = new ArrayList<>();

        for (SeatCode seatCode : seatCodes) {
            String key = holdKey(showtimeId, seatCode);
            String previous = holds.putIfAbsent(key, bookingId, holdTtlMinutes, TimeUnit.MINUTES);
            if (previous == null) {
                placed.add(seatCode);
            } else {
                unavailable.add(seatCode);
            }
        }

        if (!unavailable.isEmpty()) {
            placed.forEach(seatCode -> holds.remove(holdKey(showtimeId, seatCode), bookingId));
            throw new SeatsNotAvailableException(showtimeId, unavailable);
        }

        log.debug("Seats held: showtime={}, booking={}, seats={}, ttlMinutes={}",
                showtimeId, bookingId, seatCodes, holdTtlMinutes);
    }

    @Override
    public void releaseHolds(String showtimeId, List<SeatCode> seatCodes, String bookingId) {
        RMapCache<String, String> holds = redissonClient.getMapCache(CACHE_NAME);
        // Not a single atomic remove(key, expectedValue) any more, since a confirmed hold's
        // stored value is no longer exactly bookingId (see class javadoc) — safe as a plain
        // get-then-remove because every caller already holds the per-showtime DistributedLockPort
        // lock for the duration of this call.
        seatCodes.forEach(seatCode -> {
            String key = holdKey(showtimeId, seatCode);
            String current = holds.get(key);
            if (current != null && ownerMatches(current, bookingId)) {
                holds.remove(key);
            }
        });
        log.debug("Seats released: showtime={}, booking={}, seats={}", showtimeId, bookingId, seatCodes);
    }

    @Override
    public void confirmHold(String showtimeId, List<SeatCode> seatCodes, String previousOwnerId, String newOwnerId) {
        RMapCache<String, String> holds = redissonClient.getMapCache(CACHE_NAME);
        // previousOwnerId is always the JWT-authenticated customer confirming their own
        // pre-booking hold into newOwnerId (a bookingId) — see ReserveSeatsCommand. Stored as a
        // composite value (see class javadoc) so the customer stays attributable later, e.g. for
        // isHeldByCustomerAndBooking.
        String confirmedOwner = encodeConfirmedOwner(previousOwnerId, newOwnerId);
        List<SeatCode> placed = new ArrayList<>();
        List<SeatCode> unavailable = new ArrayList<>();

        for (SeatCode seatCode : seatCodes) {
            String key = holdKey(showtimeId, seatCode);
            // Caller already holds the per-showtime DistributedLockPort lock (same precondition
            // as holdSeats), so this remove-then-put pair is race-free without needing a single
            // atomic swap: handing over an existing pre-booking hold, or — if it already expired
            // or was never placed — falling back to a plain new hold.
            if (holds.remove(key, previousOwnerId)) {
                holds.put(key, confirmedOwner, holdTtlMinutes, TimeUnit.MINUTES);
                placed.add(seatCode);
                continue;
            }
            String previous = holds.putIfAbsent(key, confirmedOwner, holdTtlMinutes, TimeUnit.MINUTES);
            if (previous == null) {
                placed.add(seatCode);
            } else {
                unavailable.add(seatCode);
            }
        }

        if (!unavailable.isEmpty()) {
            placed.forEach(seatCode -> holds.remove(holdKey(showtimeId, seatCode), confirmedOwner));
            throw new SeatsNotAvailableException(showtimeId, unavailable);
        }

        log.debug("Seat hold confirmed: showtime={}, previousOwner={}, newOwner={}, seats={}",
                showtimeId, previousOwnerId, newOwnerId, seatCodes);
    }

    @Override
    public boolean isHeldByCustomerAndBooking(String showtimeId, List<SeatCode> seatCodes, String customerId,
                                              String bookingId) {
        RMapCache<String, String> holds = redissonClient.getMapCache(CACHE_NAME);
        String expectedOwner = encodeConfirmedOwner(customerId, bookingId);
        for (SeatCode seatCode : seatCodes) {
            String current = holds.get(holdKey(showtimeId, seatCode));
            if (current != null && !current.equals(expectedOwner)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Set<SeatCode> findHeld(String showtimeId, List<SeatCode> seatCodes) {
        if (seatCodes.isEmpty()) {
            return Set.of();
        }
        Map<String, SeatCode> seatCodeByKey = seatCodes.stream()
                .collect(Collectors.toMap(seatCode -> holdKey(showtimeId, seatCode), seatCode -> seatCode));

        RMapCache<String, String> holds = redissonClient.getMapCache(CACHE_NAME);
        // Single round trip for the whole seat map instead of one GET per seat (getAll only
        // returns entries that actually exist, so its key set IS the held subset).
        Map<String, String> present = holds.getAll(seatCodeByKey.keySet());

        return present.keySet().stream()
                .map(seatCodeByKey::get)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private String holdKey(String showtimeId, SeatCode seatCode) {
        return showtimeId + ":" + seatCode.value();
    }

    private String encodeConfirmedOwner(String customerId, String bookingId) {
        return customerId + OWNER_SEP + bookingId;
    }

    /**
     * True if {@code storedValue} (a hold's current Redis value) is owned by {@code ownerId} —
     * either a plain standalone-hold value equal to it, or a confirmed-hold composite value
     * (see class javadoc) whose bookingId component equals it. Used by {@link #releaseHolds},
     * where {@code ownerId} is always a bookingId for booking-flow releases and a customerId for
     * the standalone {@code unhold} path.
     */
    private boolean ownerMatches(String storedValue, String ownerId) {
        if (storedValue.equals(ownerId)) {
            return true;
        }
        int sepIndex = storedValue.indexOf(OWNER_SEP);
        return sepIndex >= 0 && storedValue.substring(sepIndex + OWNER_SEP.length()).equals(ownerId);
    }
}
