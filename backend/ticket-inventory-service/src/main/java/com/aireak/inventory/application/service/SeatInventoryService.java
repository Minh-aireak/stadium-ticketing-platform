package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import com.aireak.inventory.application.port.out.DistributedLockPort;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.event.SeatsReleasedEvent;
import com.aireak.inventory.domain.event.SeatsReservedEvent;
import com.aireak.inventory.domain.exception.SeatInventoryNotFoundException;
import com.aireak.inventory.domain.exception.SeatsNotAvailableException;
import com.aireak.inventory.domain.model.SeatCode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Application service: orchestrates seat reservation, release and sale confirmation.
 *
 * <p><strong>Concurrency pattern (reserve/release)</strong>:
 * <pre>
 *   1. Acquire Redisson RLock keyed on "inventory:{showtimeId}" (DistributedLockPort)
 *   2. reserve: check the Postgres catalog only for SOLD seats, then place a
 *      TTL hold in Redis (SeatHoldPort) — NO Postgres write. An abandoned hold
 *      (payment never completed) self-expires; no cleanup job needed.
 *      release: remove the Redis hold — NO Postgres write.
 *   3. Publish domain event
 *   4. Release lock (auto via executeWithLock template)
 * </pre>
 *
 * <p>Postgres is only written once per booking, on {@link #execute(ConfirmSeatsCommand)}
 * (payment success) — the temporary RESERVED state that used to be written to Postgres
 * on every reservation attempt no longer touches the DB at all. This keeps the
 * high-concurrency hot path (many people racing for the same seats) off the DB.
 *
 * <p>{@code @Version} on {@code SeatInventoryJpaEntity} still provides optimistic
 * locking as a defense layer for the (now much rarer) confirm write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatInventoryService implements ReserveSeatsUseCase, ReleaseSeatsUseCase, ConfirmSeatsUseCase {

    private static final String LOCK_PREFIX = "inventory:";
    private static final long LOCK_WAIT_SECONDS  = 5;
    private static final long LOCK_LEASE_SECONDS = 10;

    private final SeatInventoryRepository seatInventoryRepository;
    private final DistributedLockPort distributedLockPort;
    private final SeatHoldPort seatHoldPort;
    private final DomainEventPublisher eventPublisher;
    private final SeatSaleConfirmer seatSaleConfirmer;

    // Bulkhead + RateLimiter guard the hot path *before* the 5s Redisson lock wait: once the
    // semaphore/rate budget is exhausted, calls are rejected immediately (BulkheadFullException /
    // RequestNotPermitted, mapped to 503 by InventoryOverloadExceptionHandler) instead of piling
    // up Tomcat threads behind tryLock().
    @Override
    @Bulkhead(name = "seat-inventory", type = Bulkhead.Type.SEMAPHORE)
    @RateLimiter(name = "seat-inventory")
    public void execute(ReserveSeatsCommand command) {
        String lockKey = LOCK_PREFIX + command.showtimeId();
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();

        distributedLockPort.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS, () -> {
                    rejectIfAnySold(command.showtimeId(), seatCodes);

                    seatHoldPort.holdSeats(command.showtimeId(), seatCodes, command.bookingId());
                    eventPublisher.publishAll(List.of(
                            new SeatsReservedEvent(command.showtimeId(), command.bookingId(), seatCodes)));

                    log.info("Seats held: showtime={}, booking={}, seats={}",
                            command.showtimeId(), command.bookingId(), command.seatCodes());
                    return null;
                });
    }

    // Bulkhead only (no RateLimiter): release is a compensating action, not the contended
    // buy-path, but it still must not queue unbounded threads behind the same per-showtime lock.
    @Override
    @Bulkhead(name = "seat-inventory", type = Bulkhead.Type.SEMAPHORE)
    public void execute(ReleaseSeatsCommand command) {
        String lockKey = LOCK_PREFIX + command.showtimeId();
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();

        distributedLockPort.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS, () -> {
                    seatHoldPort.releaseHolds(command.showtimeId(), seatCodes, command.bookingId());
                    eventPublisher.publishAll(List.of(
                            new SeatsReleasedEvent(command.showtimeId(), seatCodes)));

                    log.info("Seats released: showtime={}, booking={}, seats={}",
                            command.showtimeId(), command.bookingId(), command.seatCodes());
                    return null;
                });
    }

    /**
     * Finalizes a sale on payment success: persists SOLD permanently and clears the hold.
     *
     * <p>The Postgres write is delegated to {@link SeatSaleConfirmer#confirmSale}, its own
     * {@code @Transactional} bean, so the JPA transaction only opens after the distributed
     * lock below is already held — not while {@code tryLock()} is still waiting for it.
     */
    @Override
    public void execute(ConfirmSeatsCommand command) {
        String lockKey = LOCK_PREFIX + command.showtimeId();
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();

        distributedLockPort.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS, () -> {
                    seatSaleConfirmer.confirmSale(command.showtimeId(), seatCodes, command.bookingId());
                    seatHoldPort.releaseHolds(command.showtimeId(), seatCodes, command.bookingId());

                    log.info("Seats sold: showtime={}, booking={}, seats={}",
                            command.showtimeId(), command.bookingId(), command.seatCodes());
                    return null;
                });
    }

    /**
     * Only SOLD is checked against Postgres — RESERVED no longer lives there (see {@link SeatHoldPort}).
     * Uses a targeted query ({@link SeatInventoryRepository#findSoldSeatCodes}) instead of loading
     * the full (EAGER-fetched) aggregate, since this runs on every reserve call while holding the
     * per-showtime lock — a showtime with thousands of seats would otherwise pay for hydrating all
     * of them just to check a handful of requested codes.
     */
    private void rejectIfAnySold(String showtimeId, List<SeatCode> seatCodes) {
        if (!seatInventoryRepository.existsByShowtimeId(showtimeId)) {
            throw new SeatInventoryNotFoundException(showtimeId);
        }

        List<SeatCode> sold = seatInventoryRepository.findSoldSeatCodes(showtimeId, seatCodes);
        if (!sold.isEmpty()) {
            throw new SeatsNotAvailableException(showtimeId, sold);
        }
    }
}
