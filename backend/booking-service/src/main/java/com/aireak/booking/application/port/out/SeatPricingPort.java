package com.aireak.booking.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Outbound port: what each seat of a showtime costs, from ticket-inventory-service — the per-seat
 * price fixed when the seat map was generated, which is what a booking's charge was the sum of.
 * Used to refund cancelled seats at exactly their own price.
 */
public interface SeatPricingPort {

    /**
     * @return a price for every one of {@code seatCodes}
     * @throws OutboundServiceUnavailableException ticket-inventory-service gave no usable answer
     */
    Map<String, BigDecimal> pricesOf(String showtimeId, List<String> seatCodes);
}
