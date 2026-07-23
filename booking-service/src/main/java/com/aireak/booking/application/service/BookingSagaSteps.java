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
    public String createDraftBooking(String idempotencyKey, String customerId, String showtimeId,
                                      List<String> seatCodes, BigDecimal amount, String currency) {
        SeatSelection seatSelection = new SeatSelection(seatCodes);
        BookingAmount bookingAmount = BookingAmount.of(amount, currency);
        Booking booking = Booking.create(customerId, showtimeId, seatSelection, bookingAmount, idempotencyKey);
        bookingRepository.save(booking);
        return booking.getBookingId();
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
