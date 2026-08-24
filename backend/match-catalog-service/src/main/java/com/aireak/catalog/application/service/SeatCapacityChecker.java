package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.CheckSeatCapacityUseCase;
import com.aireak.catalog.application.port.out.SeatCapacityQueryPort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort;
import com.aireak.catalog.domain.model.SeatCapacityCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Answers "would this many seats still fit?" from the live Redis counter, and makes the answer
 * visible in the logs.
 *
 * <p>Its own class rather than a method on {@link SoldSeatsProjectionService} because the question
 * is asked in more than one situation and answering it changes nothing: the projection asks it as
 * a pre-flight, and any caller that wants to reject a request before doing work can ask it too.
 * Keeping it separate also keeps the projection service's dependencies honest — it needs a
 * question answered, not a whole availability subsystem.
 *
 * <p><b>This is not a gate.</b> The counter can move between this read and whatever the caller
 * does next, so a {@code WITHIN_CAPACITY} answer is a snapshot, not a promise. Only
 * {@link SeatCounterUpdatePort#decrement} — one atomic script — decides authoritatively.
 * What this method really buys is the log line: an over-capacity request that shows up here, with
 * the requested count, the live count and the shortfall, is the earliest visible trace of an
 * oversell, well before anyone notices it in the seat map.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatCapacityChecker implements CheckSeatCapacityUseCase {

    private final SeatCapacityQueryPort seatCapacityQueryPort;

    @Override
    public SeatCapacityCheck checkCapacity(String showtimeId, int requestedSeats) {
        SeatCapacityCheck check = seatCapacityQueryPort.check(showtimeId, requestedSeats);

        switch (check.verdict()) {
            case EXCEEDS_CAPACITY -> log.warn(
                    "Requested seats exceed the live availability in Redis: "
                            + "showtime={}, requested={}, available={}, shortfall={}",
                    showtimeId, check.requestedSeats(), check.availableSeats(), check.shortfall());
            case UNKNOWN -> log.info(
                    "No live seat counter to check against, availability unknown: showtime={}, requested={}",
                    showtimeId, check.requestedSeats());
            case WITHIN_CAPACITY -> log.debug(
                    "Requested seats fit the live availability: "
                            + "showtime={}, requested={}, available={}, remainingAfter={}",
                    showtimeId, check.requestedSeats(), check.availableSeats(), check.remainingAfter());
        }
        return check;
    }
}
