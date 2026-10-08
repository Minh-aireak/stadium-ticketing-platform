package com.aireak.booking.application.port.out;

import java.util.Optional;

/**
 * Outbound port: a short, self-expiring claim on cancelling seats of one booking, so two cancel
 * requests for the same booking — a double click, a retry that overtook its original — do not run
 * side by side. The second is turned away at once (409) instead of racing the first into the
 * database.
 *
 * <p>A first line, not the guarantee. The booking row's {@code @Version} is what actually keeps two
 * writes from both landing, and it also covers every writer that never takes this claim (the payment
 * consumer, the reconciliation jobs, a match cancellation). So an implementation may fail open: if
 * the store behind it cannot be reached, it hands back a claim that holds nothing rather than
 * refusing every cancellation.
 */
public interface BookingCancellationLock {

    /** @return the claim, to be closed when the cancel is done; empty when another request holds it */
    Optional<Claim> tryAcquire(String bookingId);

    /** Releasing never throws: a claim that cannot be released expires on its own. */
    interface Claim extends AutoCloseable {
        @Override
        void close();
    }
}
