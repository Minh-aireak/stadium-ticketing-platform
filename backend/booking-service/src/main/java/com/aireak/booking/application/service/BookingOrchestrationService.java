package com.aireak.booking.application.service;

import com.aireak.booking.application.port.in.CreateBookingUseCase;
import com.aireak.booking.application.port.in.GetBookingUseCase;
import com.aireak.booking.application.port.in.ListBookingsUseCase;
import com.aireak.booking.application.port.in.dto.BookingCreationResult;
import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.IdempotencyClaim;
import com.aireak.booking.application.port.out.IdempotencyStore;
import com.aireak.booking.application.port.out.InventoryConfirmationRefusedException;
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
import java.util.Locale;
import java.util.Optional;

// Saga Orchestrator: coordinates booking creation flow and compensating transactions.
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingOrchestrationService implements CreateBookingUseCase, GetBookingUseCase, ListBookingsUseCase {

    private final BookingSagaSteps sagaSteps;
    private final BookingRepository bookingRepository;
    private final TicketInventoryPort ticketInventoryPort;
    private final PaymentPort paymentPort;
    private final IdempotencyStore idempotencyStore;

    // `amount` and `currency` are only ever placeholders for the draft row created in Step 1,
    // below — both are overwritten in Step 2b with what ticket-inventory-service reports (the
    // price it computes from each seat's tier, and the currency that price is quoted in) before
    // markPendingPayment/initiatePayment or any event carrying them ever runs. Never trust either
    // for the actual charge; see Step 2b and Booking#applyReservedPrice.
    @Override
    public BookingCreationResult createBooking(String idempotencyKey, String customerId, String customerEmail,
                                String showtimeId, List<String> seatCodes, BigDecimal amount, String currency) {
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
            draftOutcome = createDraftBooking(
                    idempotencyKey, customerId, customerEmail, showtimeId, seatCodes, amount, currency);
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

        // Step 2: reserve seats (REST call, no local transaction). ticket-inventory-service
        // computes and returns the authoritative total price from each seat's tier, AND the
        // currency that price is denominated in — the client-supplied `amount`/`currency` above
        // were only ever placeholders for the draft row.
        TicketInventoryPort.ReservedPrice reserved;
        try {
            reserved = ticketInventoryPort.reserveSeats(showtimeId, bookingId, seatCodes);
        } catch (Exception e) {
            log.error("Seat reservation failed for booking {}: {}", bookingId, e.getMessage());
            // Release too, exactly like every step below. "reserveSeats threw" does not mean "no
            // hold was placed": reserveSeats carries @Retry, so a read timeout on a call the
            // server actually completed leaves the hold in place and still surfaces an exception
            // here. Without this the seats stayed locked out for the full hold TTL behind a
            // booking that had just been cancelled. releaseSeats is idempotent and swallows its
            // own failures (see TicketInventoryRestAdapter#releaseSeatsFallback), so calling it
            // when there genuinely is no hold costs nothing.
            ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
            sagaSteps.cancelBooking(bookingId, "Seat reservation failed: " + e.getMessage());
            releaseIdempotencyClaim(idempotencyKey);
            throw e;
        }

        // Step 2b: persist that server-computed price AND its server-supplied currency,
        // overwriting the client-supplied placeholders, before any charge-relevant step. Seats are
        // already held at this point, so a failure here must release them like any other
        // post-reservation failure.
        try {
            sagaSteps.applyReservedPrice(bookingId, reserved.amount(), reserved.currency());
        } catch (Exception e) {
            log.error("Persisting server-computed price failed for booking {}: {}", bookingId, e.getMessage());
            ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
            sagaSteps.cancelBooking(bookingId, "State transition failed: " + e.getMessage());
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

        // Step 4: initiate payment (REST call, no local transaction) — server-computed amount and
        // currency only. The currency matters as much as the amount: payment-service multiplies by
        // 100 for some currencies and not others, so taking it from the client would hand them
        // control of the charge's magnitude even though the number itself is ours.
        try {
            paymentPort.initiatePayment(bookingId, reserved.amount(), reserved.currency());
        } catch (Exception e) {
            // A 409/422 "already being processed" would be payment-service reporting that another
            // attempt holds this booking's payment idempotency guard — the window between
            // acquiring it and committing the Payment row (PaymentService#execute). Treating that
            // as a rejection can cancel a booking whose original Stripe charge is still running,
            // so it is accepted here instead.
            //
            // Written in the conditional on purpose: no payment-service on this platform sends
            // that response. PaymentController#initiate catches DuplicatePaymentException and
            // answers 202 Accepted, which is a success status, so initiatePayment above returns
            // normally and the live "another attempt owns this" case never enters this catch at
            // all — it falls straight through to Step 5, silently. Both halves arrived in one
            // commit (6e2a1ee), so the two have never had a chance to disagree. This branch is
            // the net for the day that catch goes; PaymentRestAdapterTest pins both answers as
            // they leave the adapter, and payment-service's own
            // initiateReturnsAcceptedWhenAnIdempotentPaymentAttemptIsStillInProgress pins the 202.
            //
            // Either way the acceptance is only safe because payment-service hands the guard back
            // when its own attempt ends without a payment. Until it did, a draft row that failed
            // to commit left the guard held for its full 5-minute TTL, PaymentRestAdapter's @Retry
            // re-send was answered 202 about a payment that did not exist and never would, and the
            // booking was accepted as PENDING_PAYMENT with no payment row, no charge, and no
            // outcome BookingReconciliationJob could ever resolve it from — checkOutcome maps
            // payment-service's 404 to empty. See PaymentIdempotencyPort#release.
            if (isPaymentAlreadyBeingProcessed(e)) {
                log.warn("Payment is already being processed for booking {}; treating the " +
                        "idempotent retry as accepted", bookingId);
            } else if (e instanceof HttpStatusCodeException httpEx) {
                // A genuine validation/authorization response rejected this request before the
                // payment flow started, so compensating the seat reservation is safe.
                log.error("Payment initiation rejected for booking {}: {} {}",
                        bookingId, httpEx.getStatusCode(), e.getMessage());
                ticketInventoryPort.releaseSeats(showtimeId, bookingId, seatCodes);
                sagaSteps.cancelBooking(bookingId, "Payment initiation rejected: " + e.getMessage());
                releaseIdempotencyClaim(idempotencyKey);
                throw e;
            } else {
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

    // Outcome of {@link #createDraftBooking}: distinguishes a booking this request just created
    // from one it merely discovered after losing a unique-key race, so the caller knows whether
    // to keep driving the saga (Created) or stop and defer to the other request (WonByConcurrentRequest).
    private sealed interface DraftBookingOutcome {
        record Created(String bookingId) implements DraftBookingOutcome {}
        record WonByConcurrentRequest(Booking booking) implements DraftBookingOutcome {}
    }

    // Creates a draft booking. On a unique-key race ({@link DataIntegrityViolationException}
    // with an idempotency key present), resolves to the other request's booking ID instead of failing.
    private DraftBookingOutcome createDraftBooking(String idempotencyKey, String customerId, String customerEmail,
            String showtimeId, List<String> seatCodes, BigDecimal amount, String currency) {
        try {
            String bookingId = sagaSteps.createDraftBooking(
                    idempotencyKey, customerId, customerEmail, showtimeId, seatCodes, amount, currency);
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

    // Matches a shape payment-service does not currently produce — see the Step 4 catch above for
    // why it is kept anyway, and PaymentRestAdapterTest for what it does produce. The wording is a
    // cross-service coupling either way: DuplicatePaymentException's message is what this reads,
    // and payment-service pins it in theRefusalCarriesTheWordingBookingServiceMatchesOn.
    private boolean isPaymentAlreadyBeingProcessed(Exception exception) {
        if (!(exception instanceof HttpStatusCodeException httpException)) {
            return false;
        }
        int status = httpException.getStatusCode().value();
        if (status != 409 && status != 422) {
            return false;
        }
        return httpException.getResponseBodyAsString()
                .toLowerCase(Locale.ROOT)
                .contains("already being processed");
    }

    // For GET /api/v1/bookings/{bookingId}: lets clients poll for the terminal status of a
    // booking that was returned as PENDING_PAYMENT (including the ambiguous-payment case).
    @Override
    public Optional<Booking> getBooking(String bookingId) {
        return bookingRepository.findById(bookingId);
    }

    // For GET /api/v1/bookings ("my tickets"): the controller resolves customerId from the
    // JWT-authenticated caller, never from client input, so one customer can't list another's.
    @Override
    public BookingPage listByCustomer(String customerId, int page, int size) {
        List<Booking> items = bookingRepository.findByCustomerId(customerId, page, size);
        long total = bookingRepository.countByCustomerId(customerId);
        return new BookingPage(items, total, page, size);
    }

    // Called by PaymentResultConsumer on PAYMENT_SUCCEEDED.
    // No-ops if not PENDING_PAYMENT (redelivered/stale event).
    // Not @Transactional: markConfirmed() commits first in REQUIRES_NEW;
    // confirmReservation() is best-effort after.
    public void confirmBooking(String bookingId) {
        Booking booking = sagaSteps.findOrThrow(bookingId);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            log.error("PAYMENT_SUCCEEDED arrived after booking {} was cancelled; requesting an " +
                    "idempotent refund", bookingId);
            sagaSteps.requestRefundForLatePayment(bookingId, "Payment succeeded after booking cancellation");
            return;
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            log.warn("Ignoring PAYMENT_SUCCEEDED for booking {}: status is already {}",
                    bookingId, booking.getStatus());
            return;
        }
        sagaSteps.markConfirmed(bookingId);
        confirmInventoryReservation(bookingId, booking.getShowtimeId(), booking.getSeatSelection().seatCodes());
        log.info("Booking confirmed: id={}", bookingId);
    }

    // Called only by InventoryConfirmationReconciler for a booking its own query already found
    // CONFIRMED with inventoryConfirmed=false — unlike confirmBooking(), this has no
    // PENDING_PAYMENT guard, since the booking is expected to already be CONFIRMED.
    public void retryInventoryConfirmation(Booking booking) {
        confirmInventoryReservation(booking.getBookingId(), booking.getShowtimeId(),
                booking.getSeatSelection().seatCodes());
    }

    // Best-effort: confirmReservation is called after the booking is already durably CONFIRMED,
    // so a failure here must never undo that. Three outcomes, not two. On success, records
    // inventoryConfirmed=true. On a failure that might clear, logs and leaves it false so
    // InventoryConfirmationReconciler retries later — the Redis hold otherwise just expires via
    // TTL despite payment having succeeded (see TicketInventoryRestAdapter#confirmReservationFallback).
    // On a refusal that never will, records that instead and stops — see recordInventorySaleRefused.
    private void confirmInventoryReservation(String bookingId, String showtimeId, List<String> seatCodes) {
        try {
            ticketInventoryPort.confirmReservation(showtimeId, bookingId, seatCodes);
            sagaSteps.markInventoryConfirmed(bookingId);
        } catch (InventoryConfirmationRefusedException refused) {
            recordInventorySaleRefused(bookingId, showtimeId, seatCodes, refused);
        } catch (Exception e) {
            // Deliberately does not name confirmReservation: the try above covers
            // markInventoryConfirmed too, so a database blip on the way to recording a call that
            // actually succeeded used to be reported as ticket-inventory having failed. Either
            // way the booking keeps inventoryConfirmed=false and the reconciler re-asks, which is
            // safe -- confirmReservation is idempotent for a booking that already owns its seats.
            log.error("Could not finalize the seat sale for booking {}; left for reconciliation: {}",
                    bookingId, e.getMessage());
        }
    }

    /**
     * ticket-inventory-service will never sell these seats to this booking. The usual handling --
     * leave {@code inventoryConfirmed} false and let {@link
     * com.aireak.booking.adapter.in.scheduling.InventoryConfirmationReconciler} retry -- is wrong
     * here in a way that hides the problem rather than solving it: the answer cannot change, so
     * the booking is re-asked every five minutes for the life of the row, indistinguishable in the
     * logs from a dependency having a bad afternoon, and it permanently occupies a slot in that
     * job's capped batch.
     *
     * <p>The booking stays CONFIRMED. The customer paid, and BookingConfirmedEvent went out with
     * the same transaction as markConfirmed, so they have already been emailed a confirmation --
     * none of which can be honestly un-said by an automated step. What CAN be done is stop
     * pretending a retry will fix it and put it where somebody sees it, which is the same stance
     * payment-service takes on the mirror-image failure (see {@code UnreconciledPaymentAlertJob}:
     * "this needs a human, not a retry").
     */
    private void recordInventorySaleRefused(String bookingId, String showtimeId, List<String> seatCodes,
                                            InventoryConfirmationRefusedException refused) {
        log.error("ALERT: ticket-inventory-service will not sell seats {} to booking {} and never will " +
                        "-- the booking is CONFIRMED and paid for, so it needs a refund or a reseat by hand: " +
                        "showtime={}: {}",
                seatCodes, bookingId, showtimeId, refused.getMessage());
        try {
            sagaSteps.markInventorySaleRefused(bookingId);
        } catch (Exception e) {
            // Left for the reconciler on purpose: it will re-ask, be refused again, and come back
            // here. The alternative -- letting this escape -- would fail the Kafka listener for a
            // booking that is already durably CONFIRMED.
            log.error("Could not record the refused seat sale for booking {}; it stays in the " +
                    "reconciler's queue until this succeeds: {}", bookingId, e.getMessage());
        }
    }

    // Called by PaymentResultConsumer on PAYMENT_FAILED. showtimeId/seatCodes come from the
    // found Booking, not the event, since PaymentFailedEvent (payment-service's domain, not
    // booking's) carries neither.
    // No-ops if CONFIRMED/CANCELED (stale event — never release sold seats).
    // Not @Transactional: cancelBooking() commits first; releaseSeats() is best-effort (Redis TTL fallback).
    public void cancelBookingOnPaymentFailure(String bookingId, String reason) {
        Booking booking = sagaSteps.findOrThrow(bookingId);
        if (booking.getStatus() == BookingStatus.CONFIRMED || booking.getStatus() == BookingStatus.CANCELLED) {
            log.warn("Ignoring PAYMENT_FAILED for booking {}: status is already {}", bookingId, booking.getStatus());
            return;
        }
        sagaSteps.cancelBooking(bookingId, reason);
        ticketInventoryPort.releaseSeats(booking.getShowtimeId(), bookingId, booking.getSeatSelection().seatCodes());
        log.info("Booking cancelled due to payment failure: id={}", bookingId);
    }

    /**
     * Called by {@code MatchCancelledConsumer} for every showtime of a cancelled match: cancels
     * every active (non-CANCELLED) booking for it and refunds the ones that were CONFIRMED. The
     * match no longer exists, so — unlike {@link #cancelBookingOnPaymentFailure} — inventory
     * holds are not released here; nothing will ever book these showtimes again.
     */
    public void cancelBookingsForShowtime(String showtimeId, String reason) {
        List<Booking> activeBookings = bookingRepository.findActiveByShowtimeId(showtimeId);
        for (Booking booking : activeBookings) {
            sagaSteps.cancelBookingDueToMatchCancellation(booking.getBookingId(), reason);
        }
        log.info("Cancelled {} active booking(s) for showtime {} due to match cancellation",
                activeBookings.size(), showtimeId);
    }
}
