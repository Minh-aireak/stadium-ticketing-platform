package com.aireak.inventory.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Inbound port: read the seat map for a showtime, including live Redis hold state. */
public interface GetSeatMapUseCase {

    /**
     * @param callerId the customer asking, used only to mark which HELD seats are their own
     *                 ({@link SeatSummary#heldByCaller}) — it never changes which seats are
     *                 returned or what they cost
     */
    Optional<SeatMapResult> getSeatMap(String showtimeId, String callerId);

    record SeatMapResult(String showtimeId, List<SeatSummary> seats) {}

    /**
     * {@code status} is one of {@code AVAILABLE}/{@code HELD}/{@code SOLD}.
     *
     * <p>{@code heldByCaller} is true only for a {@code HELD} seat whose hold belongs to the
     * customer who asked. Without it every hold looks alike to the client, and a customer who
     * reloads seat selection cannot tell — or reselect — the seats they are still holding
     * themselves.
     */
    record SeatSummary(String seatCode, String status, String tier, BigDecimal price,
                       boolean heldByCaller) {}
}
