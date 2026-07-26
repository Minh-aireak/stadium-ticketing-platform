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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redisson implementation of {@link SeatHoldPort}, backed by a single
 * {@code RMapCache} ("seat-holds") shared across all showtimes — key
 * {@code "{showtimeId}:{seatCode}"}, value {@code bookingId}, per-entry TTL.
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
        seatCodes.forEach(seatCode -> holds.remove(holdKey(showtimeId, seatCode), bookingId));
        log.debug("Seats released: showtime={}, booking={}, seats={}", showtimeId, bookingId, seatCodes);
    }

    @Override
    public boolean isHeld(String showtimeId, SeatCode seatCode) {
        return redissonClient.<String, String>getMapCache(CACHE_NAME).containsKey(holdKey(showtimeId, seatCode));
    }

    private String holdKey(String showtimeId, SeatCode seatCode) {
        return showtimeId + ":" + seatCode.value();
    }
}
