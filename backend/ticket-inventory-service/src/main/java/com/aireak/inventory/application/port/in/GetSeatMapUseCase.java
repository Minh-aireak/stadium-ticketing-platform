package com.aireak.inventory.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Inbound port: read the seat map for a showtime, including live Redis hold state. */
public interface GetSeatMapUseCase {

    Optional<SeatMapResult> getSeatMap(String showtimeId);

    record SeatMapResult(String showtimeId, List<SeatSummary> seats) {}

    /** {@code status} is one of {@code AVAILABLE}/{@code HELD}/{@code SOLD}. */
    record SeatSummary(String seatCode, String status, String tier, BigDecimal price) {}
}
