package com.aireak.booking.application.service;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.DomainEventPublisher;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.SeatSelection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

// REQUIRES_NEW so each step commits independently of the orchestrator's REST calls
// (saga state survives a mid-flow crash). Own bean because @Transactional is
// proxy-based — self-invocation from the orchestrator would silently skip it.
@Component
@RequiredArgsConstructor
class BookingSagaSteps {

    private final BookingRepository bookingRepository;
    private final DomainEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String createDraftBooking(String idempotencyKey, String customerId, String customerEmail,
                                      String showtimeId, List<String> seatCodes, BigDecimal amount, String currency) {
        SeatSelection seatSelection = new SeatSelection(seatCodes);
        BookingAmount bookingAmount = BookingAmount.of(amount, currency);
        Booking booking = Booking.create(customerId, customerEmail, showtimeId, seatSelection, bookingAmount, idempotencyKey);
        bookingRepository.save(booking);
        return booking.getBookingId();
    }

    // Persists the server-computed price returned by ticketInventoryPort.reserveSeats(), replacing
    // the client-supplied placeholder passed to createDraftBooking. See Booking#applyReservedPrice.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyReservedPrice(String bookingId, BigDecimal amount, String currency) {
        Booking booking = findOrThrow(bookingId);
        booking.applyReservedPrice(BookingAmount.of(amount, currency));
        bookingRepository.save(booking);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPendingPayment(String bookingId) {
        Booking booking = findOrThrow(bookingId);
        booking.markPendingPayment();
        bookingRepository.save(booking);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelBooking(String bookingId, String reason) {
        Booking booking = findOrThrow(bookingId);
        booking.cancel(reason);
        saveAndPublish(booking);
    }

    /**
     * Cancels and, when the booking had been paid for, requests the refund in the SAME
     * transaction — see {@code Booking#cancelDueToMatchCancellation}. Nothing is returned for the
     * caller to follow up on: the outbox row is the follow-up, and unlike the HTTP call this
     * replaced it survives payment-service being down.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelBookingDueToMatchCancellation(String bookingId, String reason) {
        Booking booking = findOrThrow(bookingId);
        booking.cancelDueToMatchCancellation(reason);
        saveAndPublish(booking);
    }

    /**
     * Records that a payment landed after its booking was already cancelled, so the charge has to
     * be given back — see {@code Booking#requestRefundForLatePayment}. Its own transaction for the
     * same reason every other step here has one: the outbox row must commit or not at all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestRefundForLatePayment(String bookingId, String reason) {
        Booking booking = findOrThrow(bookingId);
        booking.requestRefundForLatePayment(reason);
        eventPublisher.publishAll(booking.pullDomainEvents());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCreationSucceeded(String bookingId) {
        Booking booking = findOrThrow(bookingId);
        booking.recordCreationSucceeded();
        eventPublisher.publishAll(booking.pullDomainEvents());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markConfirmed(String bookingId) {
        Booking booking = findOrThrow(bookingId);
        booking.confirm();
        saveAndPublish(booking);
    }

    // Own step (committed after the confirmReservation REST call, not alongside markConfirmed)
    // because it records the REST call's *outcome*, which isn't known until after markConfirmed
    // has already committed. See BookingOrchestrationService#confirmInventoryReservation.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInventoryConfirmed(String bookingId) {
        Booking booking = findOrThrow(bookingId);
        booking.markInventoryConfirmed();
        bookingRepository.save(booking);
    }

    private void saveAndPublish(Booking booking) {
        bookingRepository.save(booking);
        eventPublisher.publishAll(booking.pullDomainEvents());
    }

    // Package-private: also called directly by BookingOrchestrationService, which shares
    // this exact "find booking or throw" lookup instead of duplicating it.
    Booking findOrThrow(String bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
    }
}
