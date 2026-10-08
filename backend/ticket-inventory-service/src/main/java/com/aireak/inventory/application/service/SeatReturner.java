package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The Postgres half of giving seats back: SOLD → AVAILABLE for the seats a booking bought, and the
 * {@code SeatsReturnedEvent} outbox row for them, in one transaction. Its own bean for the same two
 * reasons as {@link SeatSaleConfirmer}, whose mirror image it is: {@code @Transactional} needs the
 * proxy, and the transaction should only open once the caller already holds the showtime lock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SeatReturner {

    private final SeatInventoryRepository seatInventoryRepository;
    private final DomainEventPublisher eventPublisher;

    /** @return the seats that went from SOLD to AVAILABLE; empty when none of them were SOLD to it */
    @Transactional
    List<SeatCode> returnSeats(String showtimeId, List<SeatCode> seatCodes, String bookingId) {
        Optional<SeatInventory> found = seatInventoryRepository.findByShowtimeIdWithSeats(showtimeId, seatCodes);
        if (found.isEmpty()) {
            // Inventory rows are never deleted, so this is a showtime that never had one: nothing
            // can have been sold, and retrying the event would only send it to the dead-letter topic.
            log.warn("No seat inventory for the showtime of a seat return, nothing to put back on sale: "
                    + "showtime={}, booking={}, seats={}", showtimeId, bookingId, seatCodes);
            return List.of();
        }
        SeatInventory inventory = found.get();
        List<SeatCode> returned = inventory.returnSeats(seatCodes, bookingId);
        if (!returned.isEmpty()) {
            seatInventoryRepository.saveSeats(inventory);
            eventPublisher.publishAll(inventory.pullDomainEvents());
        }
        return returned;
    }
}
