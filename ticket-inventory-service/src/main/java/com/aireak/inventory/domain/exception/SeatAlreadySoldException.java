package com.aireak.inventory.domain.exception;

import com.aireak.common.exception.DomainException;
import com.aireak.inventory.domain.model.SeatCode;

/** Thrown when a booking tries to confirm a seat already SOLD to a different booking. */
public class SeatAlreadySoldException extends DomainException {
    public SeatAlreadySoldException(SeatCode seatCode, String soldToBookingId, String requestingBookingId) {
        super("Seat " + seatCode.value() + " already sold to booking " + soldToBookingId +
              ", cannot confirm for booking " + requestingBookingId);
    }
}
