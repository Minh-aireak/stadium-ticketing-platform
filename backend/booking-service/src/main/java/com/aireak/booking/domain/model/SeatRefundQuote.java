package com.aireak.booking.domain.model;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * What each seat being cancelled is refunded at: its own price, as ticket-inventory-service fixed
 * it when the seat map was generated. That is the same per-seat price the booking's charge was the
 * sum of (see {@code BookingOrchestrationService#createBooking}, Step 2b), so refunding seat by seat
 * gives back exactly what each seat cost — which a booking-wide average would not, once seats of
 * different tiers share a booking.
 */
public record SeatRefundQuote(Map<String, BigDecimal> priceBySeat) {

    public SeatRefundQuote {
        priceBySeat = Map.copyOf(Objects.requireNonNull(priceBySeat, "priceBySeat must not be null"));
        priceBySeat.forEach((seat, price) -> {
            if (price.signum() < 0) {
                throw new IllegalArgumentException("Seat " + seat + " has a negative price: " + price);
            }
        });
    }

    public boolean covers(Collection<String> seatCodes) {
        return priceBySeat.keySet().containsAll(seatCodes);
    }

    public BigDecimal totalFor(Collection<String> seatCodes) {
        if (!covers(seatCodes)) {
            throw new IllegalArgumentException("No price quoted for some of " + seatCodes);
        }
        return seatCodes.stream().map(priceBySeat::get).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
