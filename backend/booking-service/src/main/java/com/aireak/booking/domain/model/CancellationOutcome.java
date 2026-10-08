package com.aireak.booking.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * What {@link Booking#cancelSeatsByCustomer} actually did.
 *
 * @param seatCodes        the seats this call cancelled, or — for {@link Kind#ALREADY_CANCELLED} —
 *                         the requested seats, every one of which was already cancelled
 * @param refundAmount     what this call asked payment-service to refund; zero for an unpaid booking
 *                         and for a repeat
 * @param quotedAmount     the seats' own prices summed, before any adjustment — differs from
 *                         {@code refundAmount} only when the booking's remaining balance did, which
 *                         is worth a log line
 * @param bookingCancelled whether the booking as a whole is now CANCELLED
 */
public record CancellationOutcome(Kind kind, List<String> seatCodes, BigDecimal refundAmount,
                                  BigDecimal quotedAmount, boolean bookingCancelled) {

    public enum Kind {
        /** This call cancelled seats. */
        CANCELLED_NOW,
        /** Nothing left to do: every requested seat was already cancelled. */
        ALREADY_CANCELLED
    }

    public CancellationOutcome {
        seatCodes = List.copyOf(seatCodes);
    }

    static CancellationOutcome alreadyCancelled(List<String> seatCodes, boolean bookingCancelled) {
        return new CancellationOutcome(Kind.ALREADY_CANCELLED, seatCodes, BigDecimal.ZERO, BigDecimal.ZERO,
                bookingCancelled);
    }

    static CancellationOutcome cancelledNow(List<String> seatCodes, BigDecimal refundAmount,
                                            BigDecimal quotedAmount, boolean bookingCancelled) {
        return new CancellationOutcome(Kind.CANCELLED_NOW, seatCodes, refundAmount, quotedAmount, bookingCancelled);
    }

    public boolean isRepeat() {
        return kind == Kind.ALREADY_CANCELLED;
    }
}
