package com.aireak.catalog.application.port.in;

/**
 * Inbound port: project a completed seat sale onto the catalog's availability numbers — the Redis
 * counter customers are shown, and the {@code showtimes.available_seats} column behind it.
 *
 * <p>Driven by {@code SeatsSoldEventConsumer}. The sale itself already happened in
 * ticket-inventory-service, so this never rejects: it applies, or it fails loudly and lets Kafka
 * redeliver.
 */
public interface ApplySoldSeatsUseCase {

    /**
     * @return {@code true} when this event moved the numbers, {@code false} when it had already
     *         been applied and was skipped
     */
    boolean applySoldSeats(String eventId, String showtimeId, int soldSeatCount);
}
