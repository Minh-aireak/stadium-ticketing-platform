package com.aireak.booking.application.service;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.DomainEventPublisher;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.booking.application.port.out.TicketInventoryPort;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.SeatSelection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Saga Orchestrator: coordinates the booking creation flow.
 *
 * <p><strong>Happy path (sequential, synchronous steps)</strong>:
 * <pre>
 *   1. Create Booking aggregate (DRAFT) + persist
 *   2. Reserve seats via REST → ticket-inventory-service
 *   3. markPendingPayment() + persist
 *   4. Initiate payment via REST → payment-service
 *   5. Publish BookingCreatedEvent (payment result comes via Kafka callback)
 * </pre>
 *
 * <p><strong>Compensating transactions</strong>:
 * <pre>
 *   If reserveSeats fails  → cancel booking, no compensation needed (seats never held)
 *   If initiatePayment fails → releaseSeats + cancel booking + publish BookingCancelledEvent
 * </pre>
 *
 * <p>Payment result (SUCCESS/FAILED) is received asynchronously via Kafka
 * in {@code PaymentResultConsumer} — this class handles only the initiation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingOrchestrationService {

    private final BookingRepository bookingRepository;
    private final TicketInventoryPort ticketInventoryPort;
    private final PaymentPort paymentPort;
    private final DomainEventPublisher eventPublisher;

    /**
     * Creates a booking and drives the saga forward.
     *
     * @return bookingId of the created booking
     */
    @Transactional
    public String createBooking(String customerId, String showtimeId,
                                List<String> seatCodes, BigDecimal amount, String currency) {
        // Step 1: Create Booking aggregate
        SeatSelection seatSelection = new SeatSelection(seatCodes);
        BookingAmount bookingAmount = BookingAmount.of(amount, currency);
        Booking booking = Booking.create(customerId, showtimeId, seatSelection, bookingAmount);
        bookingRepository.save(booking);

        String bookingId = booking.getBookingId();
        log.info("Booking created: id={}, customerId={}", bookingId, customerId);

        // Step 2: Reserve seats (sync REST — compensate on failure)
        try {
            ticketInventoryPort.reserveSeats(showtimeId, bookingId, seatCodes);
        } catch (Exception e) {
            log.error("Seat reservation failed for booking {}: {}", bookingId, e.getMessage());
            booking.cancel("Seat reservation failed: " + e.getMessage());
            bookingRepository.save(booking);
            eventPublisher.publishAll(booking.pullDomainEvents());
            throw e; // re-throw so caller gets 500/circuit open response
        }

        // Step 3: Transition to PENDING_PAYMENT
        booking.markPendingPayment();
        bookingRepository.save(booking);

        // Step 4: Initiate payment (sync REST — compensate on failure)
        try {
            paymentPort.initiatePayment(bookingId, amount, currency);
        } catch (Exception e) {
            log.error("Payment initiation failed for booking {}: {}", bookingId, e.getMessage());
            ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
            booking.cancel("Payment initiation failed: " + e.getMessage());
            bookingRepository.save(booking);
            eventPublisher.publishAll(booking.pullDomainEvents());
            throw e;
        }

        // Step 5: Publish domain events
        eventPublisher.publishAll(booking.pullDomainEvents());
        return bookingId;
    }

    /**
     * Called by PaymentResultConsumer when payment succeeds.
     * Confirms the booking and publishes BookingConfirmedEvent.
     */
    @Transactional
    public void confirmBooking(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        booking.confirm();
        bookingRepository.save(booking);
        eventPublisher.publishAll(booking.pullDomainEvents());
        log.info("Booking confirmed: id={}", bookingId);
    }

    /**
     * Called by PaymentResultConsumer when payment fails.
     * Releases seats and cancels the booking.
     */
    @Transactional
    public void cancelBookingOnPaymentFailure(String bookingId, String showtimeId,
                                               List<String> seatCodes, String reason) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
        booking.cancel(reason);
        bookingRepository.save(booking);
        eventPublisher.publishAll(booking.pullDomainEvents());
        log.info("Booking cancelled due to payment failure: id={}", bookingId);
    }
}
