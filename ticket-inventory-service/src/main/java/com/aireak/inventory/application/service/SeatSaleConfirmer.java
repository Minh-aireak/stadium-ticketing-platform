package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.exception.SeatInventoryNotFoundException;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Own bean (not a method on {@link SeatInventoryService}) so {@code @Transactional} is
 * intercepted by Spring's AOP proxy. {@link SeatInventoryService#execute(com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand)}
 * calls this from inside a Redisson lock it already holds; a self-invoked {@code @Transactional}
 * method on the same bean would silently run without a transaction (same reasoning as
 * {@code BookingSagaSteps} in booking-service).
 *
 * <p>Isolating the Postgres write behind this bean also means the JPA transaction (and its
 * pooled connection) only opens after the distributed lock is already held — not while
 * {@code tryLock()} is still waiting, which could otherwise hold a connection idle for up to
 * {@code LOCK_WAIT_SECONDS} under lock contention.
 */
@Component
@RequiredArgsConstructor
class SeatSaleConfirmer {

    private final SeatInventoryRepository seatInventoryRepository;
    private final DomainEventPublisher eventPublisher;

    /** Persists SOLD for {@code seatCodes} and publishes the resulting domain events. */
    @Transactional
    void confirmSale(String showtimeId, List<SeatCode> seatCodes, String bookingId) {
        SeatInventory inventory = seatInventoryRepository.findByShowtimeId(showtimeId)
                .orElseThrow(() -> new SeatInventoryNotFoundException(showtimeId));

        inventory.sellSeats(seatCodes, bookingId);
        seatInventoryRepository.save(inventory);
        eventPublisher.publishAll(inventory.pullDomainEvents());
    }
}
