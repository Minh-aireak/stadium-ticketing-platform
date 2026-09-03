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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *         {@link #isFreeOfHoldsByOtherOwners} verify a customer-token release actually owns the
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
    // (matches nothing when compared for isFreeOfHoldsByOtherOwners) — no ambiguity, fails closed.
    private static final String OWNER_SEP = "::";

    private final RedissonClient redissonClient;

    @Value("${inventory.hold.ttl-minutes:10}")
    private long holdTtlMinutes;

    @Override
    public void holdSeats(String showtimeId, List<SeatCode> seatCodes, String bookingId) {
        RMapCache<String, String> holds = redissonClient.getMapCache(CACHE_NAME);
        // Only what THIS call newly wrote, so the rollback below undoes exactly its own effect —
        // a seat the owner already held before this call was not placed here and must survive it.
        List<SeatCode> newlyPlaced = new ArrayList<>();
        List<SeatCode> unavailable = new ArrayList<>();

        for (SeatCode seatCode : seatCodes) {
            String key = holdKey(showtimeId, seatCode);
            String previous = holds.putIfAbsent(key, bookingId, holdTtlMinutes, TimeUnit.MINUTES);
            if (previous == null) {
                newlyPlaced.add(seatCode);
            } else if (!previous.equals(bookingId)) {
                unavailable.add(seatCode);
            }
            // else: already held by this same owner — a retried request (double-click, network
            // retry) is a success, not a conflict. Reporting the caller's own seat as taken used
            // to fail the whole call AND roll back the seats placed alongside it.
        }

        if (!unavailable.isEmpty()) {
            newlyPlaced.forEach(seatCode -> holds.remove(holdKey(showtimeId, seatCode), bookingId));
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
        // isFreeOfHoldsByOtherOwners.
        String confirmedOwner = encodeConfirmedOwner(previousOwnerId, newOwnerId);
        // Only what THIS call newly wrote — see holdSeats for why the rollback must not undo a
        // hold that was already confirmed to this same booking by an earlier attempt.
        List<SeatCode> newlyPlaced = new ArrayList<>();
        List<SeatCode> unavailable = new ArrayList<>();

        for (SeatCode seatCode : seatCodes) {
            String key = holdKey(showtimeId, seatCode);
            // Caller already holds the per-showtime DistributedLockPort lock (same precondition
            // as holdSeats), so this remove-then-put pair is race-free without needing a single
            // atomic swap: handing over an existing pre-booking hold, or — if it already expired
            // or was never placed — falling back to a plain new hold.
            if (holds.remove(key, previousOwnerId)) {
                holds.put(key, confirmedOwner, holdTtlMinutes, TimeUnit.MINUTES);
                newlyPlaced.add(seatCode);
                continue;
            }
            String previous = holds.putIfAbsent(key, confirmedOwner, holdTtlMinutes, TimeUnit.MINUTES);
            if (previous == null) {
                newlyPlaced.add(seatCode);
            } else if (!confirmedOwner.equals(previous)) {
                unavailable.add(seatCode);
            }
            // else: this exact booking already owns the hold — the confirm has simply run before.
            // booking-service's reserveSeats carries @Retry, so a read timeout on a request the
            // server actually completed re-enters here; treating that replay as "seats not
            // available" used to fail the saga and cancel a booking whose seats were correctly
            // held, leaving them locked out for the full hold TTL.
        }

        if (!unavailable.isEmpty()) {
            newlyPlaced.forEach(seatCode -> holds.remove(holdKey(showtimeId, seatCode), confirmedOwner));
            throw new SeatsNotAvailableException(showtimeId, unavailable);
        }

        log.debug("Seat hold confirmed: showtime={}, previousOwner={}, newOwner={}, seats={}",
                showtimeId, previousOwnerId, newOwnerId, seatCodes);
    }

    @Override
    public boolean isFreeOfHoldsByOtherOwners(String showtimeId, List<SeatCode> seatCodes, String customerId,
                                              String bookingId) {
        RMapCache<String, String> holds = redissonClient.getMapCache(CACHE_NAME);
        String expectedOwner = encodeConfirmedOwner(customerId, bookingId);
        for (SeatCode seatCode : seatCodes) {
            String current = holds.get(holdKey(showtimeId, seatCode));
            // A seat with no active hold passes deliberately — see the port's javadoc. Absence is
            // not ownership, which is exactly why this method is NOT named for ownership: it
            // answers "nobody else holds this", the only question the release path needs.
            if (current != null && !current.equals(expectedOwner)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<SeatCode, String> findHoldOwners(String showtimeId, List<SeatCode> seatCodes) {
        if (seatCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, SeatCode> seatCodeByKey = seatCodes.stream()
                .collect(Collectors.toMap(seatCode -> holdKey(showtimeId, seatCode), seatCode -> seatCode));

        RMapCache<String, String> holds = redissonClient.getMapCache(CACHE_NAME);
        // Single round trip for the whole seat map instead of one GET per seat (getAll only
        // returns entries that actually exist, so its key set IS the held subset). The values
        // come back in the same trip, so attributing each hold to its owner costs nothing extra.
        Map<String, String> present = holds.getAll(seatCodeByKey.keySet());

        Map<SeatCode, String> ownersBySeat = new HashMap<>();
        present.forEach((key, storedValue) ->
                ownersBySeat.put(seatCodeByKey.get(key), decodeOwningCustomer(storedValue)));
        return ownersBySeat;
    }

    private String holdKey(String showtimeId, SeatCode seatCode) {
        return showtimeId + ":" + seatCode.value();
    }

    private String encodeConfirmedOwner(String customerId, String bookingId) {
        return customerId + OWNER_SEP + bookingId;
    }

    /**
     * The customer a hold belongs to, from its stored value: the whole value for a standalone
     * pre-booking hold (whose value is the customerId — {@link #holdSeats} is only ever called
     * with one, see {@code SeatInventoryService}), or the customer component for a hold already
     * confirmed into a booking (see class javadoc).
     */
    private String decodeOwningCustomer(String storedValue) {
        int sepIndex = storedValue.indexOf(OWNER_SEP);
        return sepIndex >= 0 ? storedValue.substring(0, sepIndex) : storedValue;
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
