package com.aireak.booking.application.service;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.IdempotencyClaim;
import com.aireak.booking.application.port.out.IdempotencyStore;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.booking.application.port.out.TicketInventoryPort;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

// Saga Orchestrator: coordinates booking creation flow and compensating transactions.
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingOrchestrationService {

    private final BookingSagaSteps sagaSteps;
    private final BookingRepository bookingRepository;
    private final TicketInventoryPort ticketInventoryPort;
    private final PaymentPort paymentPort;
    private final IdempotencyStore idempotencyStore;

    public BookingCreationResult createBooking(String idempotencyKey, String customerId, String showtimeId,
                                List<String> seatCodes, BigDecimal amount, String currency) {
        if (idempotencyKey != null) {
            IdempotencyClaim claim = idempotencyStore.claim(idempotencyKey);
            if (claim instanceof IdempotencyClaim.Completed completed) {
                log.info("Idempotent replay for key {}: returning existing booking {}",
                        idempotencyKey, completed.bookingId());
                return resultFor(completed.bookingId());
            }
            if (claim instanceof IdempotencyClaim.InProgress) {
                throw new DuplicateRequestInProgressException(idempotencyKey);
            }

            // Check DB fallback if Redis key was evicted or restarted.
            Optional<Booking> existing = bookingRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                idempotencyStore.complete(idempotencyKey, existing.get().getBookingId());
                log.info("Idempotent replay for key {} (DB, Redis had missed it): returning existing booking {}",
                        idempotencyKey, existing.get().getBookingId());
                return new BookingCreationResult(existing.get().getBookingId(), existing.get().getStatus());
            }
        }

        // Step 1: create draft booking — commits immediately
        DraftBookingOutcome draftOutcome;
        try {
            draftOutcome = createDraftBooking(idempotencyKey, customerId, showtimeId, seatCodes, amount, currency);
        } catch (Exception e) {
            // Anything other than the unique-key race handled inside createDraftBooking()
            // (e.g. MaxTicketsExceededException, a transient DB error) must release the
            // claim here too — otherwise it sticks for the full 90s TTL and blocks a
            // legitimate retry with a 409, even though nothing is actually in flight.
            log.error("Draft booking creation failed for idempotencyKey={}: {}", idempotencyKey, e.getMessage());
            releaseIdempotencyClaim(idempotencyKey);
            throw e;
        }
        if (draftOutcome instanceof DraftBookingOutcome.WonByConcurrentRequest won) {
            // Lost the unique-key race — the other request already owns this booking and is
            // driving (or has already driven) the rest of the saga for it. Return its result
            // immediately, same as the other idempotency-replay branches above, instead of
            // re-running Steps 2-5 against a booking this request doesn't own. The winning
            // Booking was already read moments ago (inside createDraftBooking's catch block) —
            // reuse it instead of a second findById() round-trip for the same row.
            return new BookingCreationResult(won.booking().getBookingId(), won.booking().getStatus());
        }
        String bookingId = ((DraftBookingOutcome.Created) draftOutcome).bookingId();
        log.info("Booking created: id={}, customerId={}", bookingId, customerId);

        // Step 2: reserve seats (REST call, no local transaction)
        try {
            ticketInventoryPort.reserveSeats(showtimeId, bookingId, seatCodes);
        } catch (Exception e) {
            log.error("Seat reservation failed for booking {}: {}", bookingId, e.getMessage());
            sagaSteps.cancelBooking(bookingId, "Seat reservation failed: " + e.getMessage());
            releaseIdempotencyClaim(idempotencyKey);
            throw e;
        }

        // Step 3: mark PENDING_PAYMENT — commits immediately
        try {
            sagaSteps.markPendingPayment(bookingId);
        } catch (Exception e) {
            log.error("markPendingPayment failed for booking {}: {}", bookingId, e.getMessage());
            ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
            sagaSteps.cancelBooking(bookingId, "State transition failed: " + e.getMessage());
            releaseIdempotencyClaim(idempotencyKey);
            throw e;
        }

        // Step 4: initiate payment (REST call, no local transaction)
        try {
            paymentPort.initiatePayment(bookingId, amount, currency);
        } catch (Exception e) {
            // PaymentRestAdapter rethrows HttpStatusCodeException as-is, so it's checked directly.
            if (e instanceof HttpStatusCodeException httpEx) {
                // Definite HTTP response — payment never started, safe to compensate now.
                log.error("Payment initiation rejected for booking {}: {} {}",
                        bookingId, httpEx.getStatusCode(), e.getMessage());
                ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
                sagaSteps.cancelBooking(bookingId, "Payment initiation rejected: " + e.getMessage());
                releaseIdempotencyClaim(idempotencyKey);
                throw e;
            }

            // No HTTP response (timeout/reset/circuit open) — payment may have already
            // started. Ask payment-service directly before giving up on it: only a definite
            // SUCCEEDED/FAILED is safe to act on here (see PaymentPort#checkOutcome — a "not
            // found" result is indistinguishable from "still mid-flight" and must NOT be
            // treated as safe-to-compensate).
            Optional<PaymentPort.PaymentOutcome> outcome = paymentPort.checkOutcome(bookingId);
            if (outcome.isPresent() && outcome.get() == PaymentPort.PaymentOutcome.SUCCEEDED) {
                try {
                    log.warn("Payment initiation was ambiguous for booking {} but reconciliation " +
                            "confirmed success; confirming booking synchronously", bookingId);
                    confirmBooking(bookingId);
                    if (idempotencyKey != null) {
                        idempotencyStore.complete(idempotencyKey, bookingId);
                    }
                    // Re-read rather than assume CONFIRMED: PaymentResultConsumer runs on its
                    // own thread and may have already resolved (even cancelled, on a
                    // conflicting signal) this booking concurrently with this check.
                    return resultFor(bookingId);
                } catch (Exception confirmEx) {
                    // Most likely lost a race with PaymentResultConsumer resolving this
                    // booking concurrently (confirmBooking's own guard threw because the
                    // status was no longer PENDING_PAYMENT). Don't let a different exception
                    // type skip releaseIdempotencyClaim below — fall through to the same
                    // safe handling as an unresolved outcome.
                    log.error("Failed to apply reconciled SUCCEEDED outcome for booking {}: {}",
                            bookingId, confirmEx.getMessage());
                }
            } else if (outcome.isPresent() && outcome.get() == PaymentPort.PaymentOutcome.FAILED) {
                try {
                    log.warn("Payment initiation was ambiguous for booking {} but reconciliation " +
                            "confirmed failure; cancelling booking synchronously", bookingId);
                    ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
                    sagaSteps.cancelBooking(bookingId, "Payment failed: " + e.getMessage());
                } catch (Exception cancelEx) {
                    log.error("Failed to apply reconciled FAILED outcome for booking {}: {}",
                            bookingId, cancelEx.getMessage());
                }
            } else {
                // Still genuinely unknown — leave PENDING_PAYMENT; async PaymentResultConsumer
                // or the scheduled reconciliation job resolves it later.
                log.error("Payment initiation ambiguous for booking {}: {}", bookingId, e.getMessage());
            }
            // Client got an error, not a bookingId — release the claim so a retry re-enters
            // createBooking (the DB check above will pick up the existing row if present).
            releaseIdempotencyClaim(idempotencyKey);
            throw e;
        }

        // Step 5: publish BookingCreatedEvent
        sagaSteps.recordCreationSucceeded(bookingId);
        if (idempotencyKey != null) {
            idempotencyStore.complete(idempotencyKey, bookingId);
        }
        // Payment confirmation is always async (PaymentResultConsumer) — even a successful
        // initiatePayment call above only means payment-service accepted the request, not
        // that it succeeded. Status here is always PENDING_PAYMENT.
        return new BookingCreationResult(bookingId, BookingStatus.PENDING_PAYMENT);
    }

    // Looks up the current status for an idempotency-store-completed booking, for a replay
    // response. Falls back to PENDING_PAYMENT if the row can't be found (should not happen —
    // the store only records completion after the row is persisted).
    private BookingCreationResult resultFor(String bookingId) {
        BookingStatus status = bookingRepository.findById(bookingId)
                .map(Booking::getStatus)
                .orElse(BookingStatus.PENDING_PAYMENT);
        return new BookingCreationResult(bookingId, status);
    }

    // Result of {@link #createBooking}: the client must read {@code status}, not just presence of a bookingId, to know whether the booking is finalized. */
    public record BookingCreationResult(String bookingId, BookingStatus status) {}

    // Outcome of {@link #createDraftBooking}: distinguishes a booking this request just created
    // from one it merely discovered after losing a unique-key race, so the caller knows whether
    // to keep driving the saga (Created) or stop and defer to the other request (WonByConcurrentRequest).
    private sealed interface DraftBookingOutcome {
        record Created(String bookingId) implements DraftBookingOutcome {}
        record WonByConcurrentRequest(Booking booking) implements DraftBookingOutcome {}
    }

    // Creates a draft booking. On a unique-key race ({@link DataIntegrityViolationException}
    // with an idempotency key present), resolves to the other request's booking ID instead of failing.
    private DraftBookingOutcome createDraftBooking(String idempotencyKey, String customerId, String showtimeId,
            List<String> seatCodes, BigDecimal amount, String currency) {
        try {
            String bookingId = sagaSteps.createDraftBooking(
                    idempotencyKey, customerId, showtimeId, seatCodes, amount, currency);
            return new DraftBookingOutcome.Created(bookingId);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey == null) {
                throw e;
            }
            // Lost the race — the other request's row is now the source of truth; return it.
            Booking winner = bookingRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            idempotencyStore.complete(idempotencyKey, winner.getBookingId());
            log.info("Idempotency key {} raced with a concurrent request: returning booking {}",
                    idempotencyKey, winner.getBookingId());
            return new DraftBookingOutcome.WonByConcurrentRequest(winner);
        }
    }

    private void releaseIdempotencyClaim(String idempotencyKey) {
        if (idempotencyKey != null) {
            idempotencyStore.release(idempotencyKey);
        }
    }

    // For GET /api/v1/bookings/{bookingId}: lets clients poll for the terminal status of a
    // booking that was returned as PENDING_PAYMENT (including the ambiguous-payment case).
    public Optional<Booking> getBooking(String bookingId) {
        return bookingRepository.findById(bookingId);
    }

    // Called by PaymentResultConsumer on PAYMENT_SUCCEEDED.
    // No-ops if not PENDING_PAYMENT (redelivered/stale event).
    // Not @Transactional: markConfirmed() commits first in REQUIRES_NEW;
    // confirmReservation() is best-effort after.
    public void confirmBooking(String bookingId) {
        Booking booking = sagaSteps.findOrThrow(bookingId);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            log.warn("Ignoring PAYMENT_SUCCEEDED for booking {}: status is already {}",
                    bookingId, booking.getStatus());
            return;
        }
        sagaSteps.markConfirmed(bookingId);
        ticketInventoryPort.confirmReservation(
                booking.getShowtimeId(), bookingId, booking.getSeatSelection().seatCodes());
        log.info("Booking confirmed: id={}", bookingId);
    }

    // Called by PaymentResultConsumer on PAYMENT_FAILED.
    // No-ops if CONFIRMED/CANCELED (stale event — never release sold seats).
    // Not @Transactional: cancelBooking() commits first; releaseSeats() is best-effort (Redis TTL fallback).
    public void cancelBookingOnPaymentFailure(String bookingId, String showtimeId, List<String> seatCodes, String reason) {
        Booking booking = sagaSteps.findOrThrow(bookingId);
        if (booking.getStatus() == BookingStatus.CONFIRMED || booking.getStatus() == BookingStatus.CANCELLED) {
            log.warn("Ignoring PAYMENT_FAILED for booking {}: status is already {}", bookingId, booking.getStatus());
            return;
        }
        sagaSteps.cancelBooking(bookingId, reason);
        ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
        log.info("Booking cancelled due to payment failure: id={}", bookingId);
    }
}