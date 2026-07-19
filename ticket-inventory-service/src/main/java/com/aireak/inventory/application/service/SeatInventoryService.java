package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;
import com.aireak.inventory.application.port.out.DistributedLockPort;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.exception.SeatInventoryNotFoundException;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Application service: orchestrates seat reservation and release.
 *
 * <p><strong>Concurrency pattern</strong>:
 * <pre>
 *   1. Acquire Redisson RLock keyed on "inventory:{showtimeId}"
 *   2. Load SeatInventory from DB (within lock scope)
 *   3. Call aggregate method (business rule check + state mutation)
 *   4. Persist updated inventory
 *   5. Publish domain events
 *   6. Release lock (auto via executeWithLock template)
 * </pre>
 *
 * <p>@Version on JPA entity provides optimistic lock as defense layer 2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatInventoryService implements ReserveSeatsUseCase, ReleaseSeatsUseCase {

    private static final String LOCK_PREFIX = "inventory:";
    private static final long LOCK_WAIT_SECONDS  = 5;
    private static final long LOCK_LEASE_SECONDS = 10;

    private final SeatInventoryRepository seatInventoryRepository;
    private final DistributedLockPort distributedLockPort;
    private final DomainEventPublisher eventPublisher;

    @Override
    @Transactional
    public void execute(ReserveSeatsCommand command) {
        String lockKey = LOCK_PREFIX + command.showtimeId();
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();

        distributedLockPort.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS, () -> {
                    SeatInventory inventory = seatInventoryRepository
                            .findByShowtimeId(command.showtimeId())
                            .orElseThrow(() -> new SeatInventoryNotFoundException(command.showtimeId()));

                    inventory.reserveSeats(seatCodes, command.bookingId());
                    seatInventoryRepository.save(inventory);
                    eventPublisher.publishAll(inventory.pullDomainEvents());

                    log.info("Seats reserved: showtime={}, booking={}, seats={}",
                            command.showtimeId(), command.bookingId(), command.seatCodes());
                    return null;
                });
    }

    @Override
    @Transactional
    public void execute(ReleaseSeatsCommand command) {
        String lockKey = LOCK_PREFIX + command.showtimeId();
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();

        distributedLockPort.executeWithLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS,
                TimeUnit.SECONDS, () -> {
                    SeatInventory inventory = seatInventoryRepository
                            .findByShowtimeId(command.showtimeId())
                            .orElseThrow(() -> new SeatInventoryNotFoundException(command.showtimeId()));

                    inventory.releaseSeats(seatCodes);
                    seatInventoryRepository.save(inventory);
                    eventPublisher.publishAll(inventory.pullDomainEvents());

                    log.info("Seats released: showtime={}, booking={}", command.showtimeId(), command.bookingId());
                    return null;
                });
    }
}
