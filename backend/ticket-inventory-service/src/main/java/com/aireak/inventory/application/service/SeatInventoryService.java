package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.HoldSeatsUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.UnholdSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;
import com.aireak.inventory.application.port.in.command.HoldSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import com.aireak.inventory.application.port.in.command.UnholdSeatsCommand;
import com.aireak.inventory.application.port.out.DistributedLockPort;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.application.port.out.ShowtimeCatalogPort;
import com.aireak.inventory.domain.event.SeatsReleasedEvent;
import com.aireak.inventory.domain.event.SeatsReservedEvent;
import com.aireak.inventory.domain.exception.SeatInventoryNotFoundException;
import com.aireak.inventory.domain.exception.SeatsNotAvailableException;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatStatus;
import com.aireak.common.exception.ForbiddenException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Application service: orchestrates seat reservation, release and sale confirmation.
 *
 * <p>{@link #execute(HoldSeatsCommand)}/{@link #execute(UnholdSeatsCommand)} are a standalone
 * pre-booking variant of the same hold mechanism, owned by the customer instead of a booking —
 * called directly by the frontend at seat-selection time, before any booking exists.
 * {@link #execute(ReserveSeatsCommand)} confirms that pre-existing hold over into the booking
 * (see {@link SeatHoldPort#confirmHold}) instead of re-acquiring it from scratch.
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
 * <p>The confirm write's second layer, behind the Redisson lock, is {@link Seat#sell} refusing a
 * seat already SOLD to a different booking. It is NOT a JPA {@code @Version}: this class used to
 * claim one on {@code SeatInventoryJpaEntity}, but that column never incremented — selling a seat
 * writes to {@code seats}, which JPA does not count as a change to the owning entity — and it was
 * dropped in V6. See {@code SeatInventoryJpaEntity} for the full reasoning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatInventoryService implements ReserveSeatsUseCase, ReleaseSeatsUseCase, ConfirmSeatsUseCase,
        HoldSeatsUseCase, UnholdSeatsUseCase {

    // Package-private: SeatReturnService takes the same per-showtime lock, so a seat going back on
    // sale can never interleave with a reserve or a confirm of the same showtime.
    static final String LOCK_PREFIX = "inventory:";
    static final long LOCK_WAIT_SECONDS  = 5;
    static final long LOCK_LEASE_SECONDS = 10;

    private final SeatInventoryRepository seatInventoryRepository;
    private final DistributedLockPort distributedLockPort;
    private final SeatHoldPort seatHoldPort;
    private final DomainEventPublisher eventPublisher;
    private final SeatSaleConfirmer seatSaleConfirmer;
    private final ShowtimeCatalogPort showtimeCatalogPort;

    // Bulkhead + RateLimiter guard the hot path *before* the 5s Redisson lock wait: once the
    // semaphore/rate budget is exhausted, calls are rejected immediately (BulkheadFullException /
    // RequestNotPermitted, mapped to 503 by InventoryOverloadExceptionHandler) instead of piling
    // up Tomcat threads behind tryLock().
    // Only the seat-occupation decision (price validation + Redis hold) needs to be serialized
    // per-showtime, so only that part runs inside the lock — see #publishReservedEventOrCompensate
    // for why the outbox write happens after the lock is released.
    @Override
    @Bulkhead(name = "seat-inventory", type = Bulkhead.Type.SEMAPHORE)
    @RateLimiter(name = "seat-inventory")
    public BigDecimal execute(ReserveSeatsCommand command) {
        showtimeCatalogPort.requireBookable(command.showtimeId());
        String lockKey = LOCK_PREFIX + command.showtimeId();
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();

        BigDecimal totalPrice = distributedLockPort.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS, () -> {
                    BigDecimal price = validateAndPriceSeats(command.showtimeId(), seatCodes);
                    // Confirms the customer's own pre-booking hold (see HoldSeatsUseCase) into this
                    // booking, or places a fresh hold if none exists (never pre-held, or expired) —
                    // see SeatHoldPort#confirmHold.
                    seatHoldPort.confirmHold(command.showtimeId(), seatCodes, command.customerId(), command.bookingId());
                    return price;
                });

        publishReservedEventOrCompensate(command, seatCodes);

        log.info("Seats held: showtime={}, booking={}, seats={}",
                command.showtimeId(), command.bookingId(), command.seatCodes());
        return totalPrice;
    }

    /**
     * If the outbox write fails, the Redis hold placed just above would otherwise become a
     * phantom hold: nothing downstream ever sees {@link SeatsReservedEvent} (no booking saga is
     * driving it), yet the seats stay locked out from every other buyer until the hold's own TTL
     * expires. Releasing the hold here turns that failure into an immediate "reservation failed,
     * try again" instead of a silent 10-minute seat lockout.
     *
     * <p>Runs after the distributed lock is released — releasing a hold is a per-key, per-owner
     * removal ({@link SeatHoldPort#releaseHolds}) that doesn't need the per-showtime lock for
     * correctness, and this keeps the lock's critical section limited to the actual occupation
     * decision.
     *
     * <p><strong>Accepted risk, not handled here</strong>: a hard crash between the Redis hold
     * above and this try block (process killed, box loses power) leaves the hold in place with no
     * outbox row and no way to compensate — out of scope by design. It self-cleans via the hold's
     * own TTL (10 minutes, see {@code RedissonSeatHoldAdapter}); nothing acts on a hold with no
     * corresponding booking in progress, so an expired phantom hold is harmless.
     */
    private void publishReservedEventOrCompensate(ReserveSeatsCommand command, List<SeatCode> seatCodes) {
        try {
            eventPublisher.publishAll(List.of(
                    new SeatsReservedEvent(command.showtimeId(), command.bookingId(), seatCodes)));
        } catch (RuntimeException e) {
            log.error("Failed to publish SeatsReservedEvent, releasing seat holds to avoid a phantom hold: " +
                            "showtime={}, booking={}, seats={}",
                    command.showtimeId(), command.bookingId(), command.seatCodes(), e);
            seatHoldPort.releaseHolds(command.showtimeId(), seatCodes, command.bookingId());
            throw e;
        }
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
                    // requestingCustomerId is only set when the caller used a real customer
                    // token (see ReleaseSeatsCommand javadoc) — verify inside the same lock as
                    // the actual release so there's no gap between the check and the effect.
                    // An internal-service-token call skips this: it's already trusted the same
                    // way confirm is (see SeatInventoryController#requireInternalService).
                    if (command.requestingCustomerId() != null && !seatHoldPort.isFreeOfHoldsByOtherOwners(
                            command.showtimeId(), seatCodes, command.requestingCustomerId(), command.bookingId())) {
                        throw new ForbiddenException(
                                "Caller does not own the reservation being released");
                    }
                    seatHoldPort.releaseHolds(command.showtimeId(), seatCodes, command.bookingId());
                    eventPublisher.publishAll(List.of(
                            new SeatsReleasedEvent(command.showtimeId(), seatCodes)));

                    log.info("Seats released: showtime={}, booking={}, seats={}",
                            command.showtimeId(), command.bookingId(), command.seatCodes());
                    return null;
                });
    }

    /**
     * Standalone pre-booking hold (see {@link HoldSeatsUseCase}) — called directly by the
     * frontend as soon as a customer selects a seat, well before a booking exists. Same
     * lock/price-validation pattern as {@link #execute(ReserveSeatsCommand)}, but owned by the
     * customer rather than a booking, and does not publish {@link SeatsReservedEvent}: an
     * abandoned pre-booking hold that never becomes a booking must not look like one to any
     * downstream consumer.
     */
    @Override
    @Bulkhead(name = "seat-inventory", type = Bulkhead.Type.SEMAPHORE)
    @RateLimiter(name = "seat-inventory")
    public BigDecimal execute(HoldSeatsCommand command) {
        showtimeCatalogPort.requireBookable(command.showtimeId());
        String lockKey = LOCK_PREFIX + command.showtimeId();
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();

        BigDecimal totalPrice = distributedLockPort.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS, () -> {
                    BigDecimal price = validateAndPriceSeats(command.showtimeId(), seatCodes);
                    seatHoldPort.holdSeats(command.showtimeId(), seatCodes, command.customerId());
                    return price;
                });

        log.info("Seats pre-held: showtime={}, customer={}, seats={}",
                command.showtimeId(), command.customerId(), command.seatCodes());
        return totalPrice;
    }

    /** Releases a standalone pre-booking hold (see {@link UnholdSeatsUseCase}) — deselect, or leaving the page. */
    @Override
    @Bulkhead(name = "seat-inventory", type = Bulkhead.Type.SEMAPHORE)
    public void execute(UnholdSeatsCommand command) {
        String lockKey = LOCK_PREFIX + command.showtimeId();
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();

        distributedLockPort.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS, () -> {
                    seatHoldPort.releaseHolds(command.showtimeId(), seatCodes, command.customerId());
                    log.info("Seats un-held: showtime={}, customer={}, seats={}",
                            command.showtimeId(), command.customerId(), command.seatCodes());
                    return null;
                });
    }

    /**
     * Finalizes a sale on payment success: persists SOLD permanently and clears the hold.
     *
     * <p>The Postgres write is delegated to {@link SeatSaleConfirmer#confirmSale}, its own
     * {@code @Transactional} bean, so the JPA transaction only opens after the distributed
     * lock below is already held — not while {@code tryLock()} is still waiting for it.
     *
     * <p><strong>No {@code @Bulkhead} or {@code @RateLimiter} here, unlike every other method on
     * this class</strong>, and not by oversight. Those shed load on the contended buy path, where
     * a rejected request costs a customer a retry. This runs after the money has already been
     * taken: shedding it leaves a paid booking whose seats were never marked SOLD, which is worse
     * than queueing behind the lock. It is also internal-only (see
     * {@code SeatInventoryController#requireInternalService}) and bounded by the payment rate
     * rather than by public traffic, so there is nothing here for a limiter to shape.
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
     * Validates the requested seats against Postgres — only SOLD is checked; RESERVED no longer
     * lives there (see {@link SeatHoldPort}) — and returns their total price. Uses a targeted
     * query ({@link SeatInventoryRepository#findSeatsByCodes}) instead of loading the full
     * (EAGER-fetched) aggregate, since this runs on every reserve call while holding the
     * per-showtime lock — a showtime with thousands of seats would otherwise pay for hydrating
     * all of them just to price/check a handful of requested codes.
     *
     * <p>The returned total is the sole source of truth for what a booking is charged — see
     * {@code SeatInventoryController#reserve} and booking-service's {@code TicketInventoryPort};
     * a client-supplied amount is never used.
     */
    private BigDecimal validateAndPriceSeats(String showtimeId, List<SeatCode> seatCodes) {
        if (!seatInventoryRepository.existsByShowtimeId(showtimeId)) {
            throw new SeatInventoryNotFoundException(showtimeId);
        }

        Map<SeatCode, Seat> bySeatCode = seatInventoryRepository.findSeatsByCodes(showtimeId, seatCodes)
                .stream().collect(Collectors.toMap(Seat::getSeatCode, Function.identity()));

        List<SeatCode> missing = seatCodes.stream().filter(code -> !bySeatCode.containsKey(code)).toList();
        if (!missing.isEmpty()) {
            throw new SeatsNotAvailableException(showtimeId, missing);
        }

        List<SeatCode> sold = seatCodes.stream()
                .filter(code -> bySeatCode.get(code).getStatus() == SeatStatus.SOLD)
                .toList();
        if (!sold.isEmpty()) {
            throw new SeatsNotAvailableException(showtimeId, sold);
        }

        return seatCodes.stream()
                .map(code -> bySeatCode.get(code).getPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
