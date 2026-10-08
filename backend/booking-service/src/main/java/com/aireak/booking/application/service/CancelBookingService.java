package com.aireak.booking.application.service;

import com.aireak.booking.application.port.in.CancelBookingCommand;
import com.aireak.booking.application.port.in.CancelBookingUseCase;
import com.aireak.booking.application.port.out.BookingCancellationLock;
import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.CancelledSeatsStore;
import com.aireak.booking.application.port.out.SeatPricingPort;
import com.aireak.booking.application.port.out.ShowtimeSchedulePort;
import com.aireak.booking.domain.exception.CancellationConflictException;
import com.aireak.booking.domain.exception.TicketIssuanceInProgressException;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.CancellationOutcome;
import com.aireak.booking.domain.model.CancellationWindow;
import com.aireak.booking.domain.model.CustomerCancellation;
import com.aireak.booking.domain.model.SeatRefundQuote;
import com.aireak.common.exception.DomainException;
import com.aireak.common.exception.IdentityMismatchException;
import com.aireak.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A customer cancels seats of their own booking (FR-21): all of an unpaid one, or any of a paid one
 * until the cancellation deadline, with each paid seat refunded at its own price.
 *
 * <pre>
 *   1. read the booking                   unknown → 404, someone else's → 403
 *   2. Redis: seats already recorded?     yes → 200, nothing else runs
 *   3. plan against the committed row     a repeat → bring Redis in line, 200
 *                                         DRAFT / foreign seats / part of an unpaid booking → 422
 *                                         paid, sale not finalized yet → 409
 *   4. paid seats: deadline + refund quote, read from catalog and inventory BEFORE the lock
 *   5. Redis lock, 5 s                    held by another request → 409 at once
 *   6. one transaction                    re-decide on the current row, write it and the outbox
 *                                         (refund request, seat-return request, cancelled email)
 *   7. Redis: record the cancelled seats, release the lock
 * </pre>
 *
 * <p>Only this class's job is the order of those steps. What may be cancelled and what it costs is
 * {@link Booking}'s; the transaction boundary is {@link BookingSagaSteps}'; the stores and the remote
 * reads sit behind ports. Nothing after step 6 can lose the follow-up work: the refund and the seat
 * return are outbox rows committed with the cancellation, so payment-service and
 * ticket-inventory-service act on them even if they are down at this moment, and no call to either
 * is made once the lock is held.
 *
 * <p>Every branch logs one line saying what happened, under the request's correlation id; the last
 * line of a successful cancel carries the outcome and how long it took.
 */
@Slf4j
@Service
public class CancelBookingService implements CancelBookingUseCase {

    static final String CUSTOMER_CANCELLATION_REASON = "Cancelled by the customer";

    private final BookingRepository bookingRepository;
    private final BookingSagaSteps sagaSteps;
    private final BookingCancellationLock cancellationLock;
    private final CancelledSeatsStore cancelledSeatsStore;
    private final ShowtimeSchedulePort showtimeSchedulePort;
    private final SeatPricingPort seatPricingPort;
    private final Clock clock;
    private final Duration cutoff;

    public CancelBookingService(BookingRepository bookingRepository,
                                BookingSagaSteps sagaSteps,
                                BookingCancellationLock cancellationLock,
                                CancelledSeatsStore cancelledSeatsStore,
                                ShowtimeSchedulePort showtimeSchedulePort,
                                SeatPricingPort seatPricingPort,
                                Clock clock,
                                @Value("${booking.cancellation.cutoff-hours:24}") long cutoffHours) {
        this.bookingRepository = bookingRepository;
        this.sagaSteps = sagaSteps;
        this.cancellationLock = cancellationLock;
        this.cancelledSeatsStore = cancelledSeatsStore;
        this.showtimeSchedulePort = showtimeSchedulePort;
        this.seatPricingPort = seatPricingPort;
        this.clock = clock;
        this.cutoff = Duration.ofHours(cutoffHours);
    }

    @Override
    public Booking cancelBooking(CancelBookingCommand command) {
        long startedAt = System.nanoTime();
        log.info("Cancel requested: bookingId={}, customerId={}, seats={}",
                command.bookingId(), command.requestingCustomerId(), describeRequest(command));
        try {
            return cancel(command, startedAt);
        } catch (DomainException refused) {
            log.info("Cancel refused by a booking rule: bookingId={}, reason={}, elapsedMs={}",
                    command.bookingId(), refused.getMessage(), elapsedMs(startedAt));
            throw refused;
        } catch (TicketIssuanceInProgressException | CancellationInProgressException
                 | CancellationConflictException retryLater) {
            log.warn("Cancel turned away for now, the client may retry: bookingId={}, reason={}, elapsedMs={}",
                    command.bookingId(), retryLater.getMessage(), elapsedMs(startedAt));
            throw retryLater;
        }
    }

    private Booking cancel(CancelBookingCommand command, long startedAt) {
        String bookingId = command.bookingId();
        Booking booking = loadOwnedBooking(command);

        if (isRecordedCancelled(booking, command)) {
            log.info("Cancel is a repeat, answered from Redis without the lock or a write: bookingId={}, "
                    + "cancelledSeats={}, elapsedMs={}", bookingId, booking.getCancelledSeatCodes(), elapsedMs(startedAt));
            return booking;
        }

        CustomerCancellation plan = booking.planCustomerCancellation(command.seatCodes());
        if (plan instanceof CustomerCancellation.AlreadyCancelled) {
            cancelledSeatsStore.record(bookingId, booking.getCancelledSeatCodes());
            log.info("Cancel is a repeat, the database already has these seats cancelled; Redis brought in "
                            + "line: bookingId={}, cancelledSeats={}, elapsedMs={}",
                    bookingId, booking.getCancelledSeatCodes(), elapsedMs(startedAt));
            return booking;
        }
        log.info("Cancel planned: bookingId={}, status={}, plan={}, seats={}",
                bookingId, booking.getStatus(), plan.getClass().getSimpleName(), plan.seatCodes());

        SeatRefundQuote refundQuote = plan instanceof CustomerCancellation.Paid paid ? quote(booking, paid) : null;

        BookingCancellationLock.Claim claim = cancellationLock.tryAcquire(bookingId)
                .orElseThrow(() -> new CancellationInProgressException(bookingId));
        CancellationOutcome outcome;
        Booking cancelled;
        try (claim) {
            outcome = applyInTransaction(bookingId, command.seatCodes(), refundQuote);
            cancelled = sagaSteps.findOrThrow(bookingId);
            cancelledSeatsStore.record(bookingId, cancelled.getCancelledSeatCodes());
        }
        logOutcome(cancelled, outcome, startedAt);
        return cancelled;
    }

    private Booking loadOwnedBooking(CancelBookingCommand command) {
        Booking booking = bookingRepository.findById(command.bookingId()).orElseThrow(() -> {
            log.info("Cancel refused, no such booking: bookingId={}", command.bookingId());
            return new ResourceNotFoundException("Booking not found: " + command.bookingId());
        });
        if (!booking.getCustomerId().equals(command.requestingCustomerId())) {
            log.warn("Cancel refused, the booking belongs to another customer: bookingId={}, callerId={}",
                    command.bookingId(), command.requestingCustomerId());
            throw new IdentityMismatchException("Booking does not belong to the authenticated caller");
        }
        return booking;
    }

    private boolean isRecordedCancelled(Booking booking, CancelBookingCommand command) {
        Set<String> recorded = cancelledSeatsStore.find(booking.getBookingId());
        if (recorded.isEmpty()) {
            return false;
        }
        List<String> requested = command.isWholeBooking() ? booking.getSeatSelection().seatCodes() : command.seatCodes();
        return recorded.containsAll(requested);
    }

    /**
     * The two reads a paid cancellation needs, done before the lock so the lock only ever covers a
     * local write: whether the deadline has passed, and what the seats cost.
     */
    private SeatRefundQuote quote(Booking booking, CustomerCancellation.Paid paid) {
        CancellationWindow window = new CancellationWindow(showtimeSchedulePort.kickoffOf(booking.getShowtimeId()), cutoff);
        window.requireOpenAt(clock.instant());
        SeatRefundQuote quote = new SeatRefundQuote(seatPricingPort.pricesOf(booking.getShowtimeId(), paid.seatCodes()));
        log.info("Paid seats are inside the cancellation window, refund quoted: bookingId={}, seats={}, "
                        + "refund={} {}, cancellableUntil={}",
                booking.getBookingId(), paid.seatCodes(), quote.totalFor(paid.seatCodes()),
                booking.getAmount().currency(), window.closesAt());
        return quote;
    }

    /**
     * One retry on an optimistic-lock failure, because the writer that beat this one is usually the
     * payment consumer or a reconciliation job, which never take the cancellation lock — and the
     * retry re-decides on the row they left, so it cannot apply a stale decision.
     */
    private CancellationOutcome applyInTransaction(String bookingId, List<String> seatCodes, SeatRefundQuote refundQuote) {
        try {
            return sagaSteps.cancelSeatsByCustomer(bookingId, seatCodes, refundQuote, CUSTOMER_CANCELLATION_REASON);
        } catch (OptimisticLockingFailureException concurrentWrite) {
            log.warn("Booking was written by someone else mid-cancel, retrying once on the fresh row: bookingId={}, "
                    + "cause={}", bookingId, concurrentWrite.getMessage());
            try {
                return sagaSteps.cancelSeatsByCustomer(bookingId, seatCodes, refundQuote, CUSTOMER_CANCELLATION_REASON);
            } catch (OptimisticLockingFailureException again) {
                throw new CancellationConflictException(bookingId, again);
            }
        }
    }

    private void logOutcome(Booking cancelled, CancellationOutcome outcome, long startedAt) {
        if (outcome.isRepeat()) {
            log.info("Cancel is a repeat, every requested seat was cancelled by the time the lock was held: "
                    + "bookingId={}, seats={}, elapsedMs={}", cancelled.getBookingId(), outcome.seatCodes(), elapsedMs(startedAt));
            return;
        }
        if (outcome.quotedAmount().signum() > 0 && outcome.refundAmount().compareTo(outcome.quotedAmount()) != 0) {
            log.warn("Refund settled against the booking's remaining balance, not the seats' quoted prices: "
                            + "bookingId={}, quoted={}, refund={}, bookingAmount={}",
                    cancelled.getBookingId(), outcome.quotedAmount(), outcome.refundAmount(), cancelled.getAmount().amount());
        }
        log.info("Seats cancelled by the customer: bookingId={}, seats={}, refund={} {}, bookingStatus={}, "
                        + "seatsLeft={}, refundedTotal={}, elapsedMs={}",
                cancelled.getBookingId(), outcome.seatCodes(), outcome.refundAmount(), cancelled.getAmount().currency(),
                cancelled.getStatus(), cancelled.activeSeatCodes(), cancelled.getRefundedAmount(), elapsedMs(startedAt));
    }

    private static Object describeRequest(CancelBookingCommand command) {
        return command.isWholeBooking() ? "ALL" : command.seatCodes();
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
