package com.aireak.booking.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * @param bookingId            the booking to cancel seats of
 * @param requestingCustomerId the JWT-authenticated caller; must own the booking
 * @param seatCodes            the seats to cancel; empty means every seat the booking still holds
 */
public record CancelBookingCommand(String bookingId, String requestingCustomerId, List<String> seatCodes) {

    public CancelBookingCommand {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(requestingCustomerId, "requestingCustomerId must not be null");
        seatCodes = seatCodes == null ? List.of() : List.copyOf(seatCodes);
    }

    public boolean isWholeBooking() {
        return seatCodes.isEmpty();
    }
}
