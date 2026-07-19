package com.aireak.inventory.domain.exception;

import com.aireak.common.exception.DomainException;
import com.aireak.inventory.domain.model.SeatCode;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when one or more requested seats are not AVAILABLE. */
public class SeatsNotAvailableException extends DomainException {
    public SeatsNotAvailableException(String showtimeId, List<SeatCode> unavailableSeats) {
        super("Seats not available for showtime " + showtimeId + ": " +
              unavailableSeats.stream().map(SeatCode::value).collect(Collectors.joining(", ")));
    }
}
