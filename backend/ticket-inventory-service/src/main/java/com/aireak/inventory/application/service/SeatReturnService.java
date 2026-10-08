package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.ReturnSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ReturnSeatsCommand;
import com.aireak.inventory.application.port.out.DistributedLockPort;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.domain.model.SeatCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Gives back seats a customer cancelled, off booking-service's {@code SeatsReturnRequestedEvent}.
 *
 * <pre>
 *   under the per-showtime lock every seat operation takes:
 *     1. Postgres  SOLD → AVAILABLE for seats SOLD to this booking, + SeatsReturnedEvent (one tx)
 *     2. Redis     drop this booking's holds on the seats (an unpaid booking only ever had holds)
 * </pre>
 *
 * Postgres first: it is the durable record, and a failure between the two leaves the event to be
 * retried, which finds the seats already AVAILABLE — so no second SeatsReturnedEvent, no seat counted
 * back twice — and drops the holds it missed. The lock is why nobody can take a returned seat in
 * between: a hold needs the same lock.
 *
 * <p>Not a bulkhead or rate limiter, like {@code SeatInventoryService#execute(ConfirmSeatsCommand)}:
 * this runs off a Kafka listener at the pace of cancellations, not of public traffic, and shedding it
 * would leave seats a customer gave up locked out of sale.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReturnService implements ReturnSeatsUseCase {

    private final DistributedLockPort distributedLockPort;
    private final SeatReturner seatReturner;
    private final SeatHoldPort seatHoldPort;

    @Override
    public void execute(ReturnSeatsCommand command) {
        List<SeatCode> seatCodes = command.seatCodes().stream().map(SeatCode::new).toList();
        String lockKey = SeatInventoryService.LOCK_PREFIX + command.showtimeId();

        distributedLockPort.executeWithLock(lockKey, SeatInventoryService.LOCK_WAIT_SECONDS,
                SeatInventoryService.LOCK_LEASE_SECONDS, TimeUnit.SECONDS, () -> {
                    List<SeatCode> backOnSale = seatReturner.returnSeats(command.showtimeId(), seatCodes, command.bookingId());
                    seatHoldPort.releaseHolds(command.showtimeId(), seatCodes, command.bookingId());
                    log.info("Seats given back by a cancelled booking: showtime={}, booking={}, requested={}, "
                                    + "soldToAvailable={}, holdsReleased=true",
                            command.showtimeId(), command.bookingId(), command.seatCodes(),
                            backOnSale.stream().map(SeatCode::value).toList());
                    return null;
                });
    }
}
