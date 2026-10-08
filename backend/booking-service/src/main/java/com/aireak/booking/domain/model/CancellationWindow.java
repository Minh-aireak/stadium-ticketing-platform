package com.aireak.booking.domain.model;

import com.aireak.booking.domain.exception.CancellationWindowClosedException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How long a paid ticket stays cancellable: until {@code cutoff} before its showtime kicks off.
 * Unpaid bookings are not subject to it — nothing has been charged, and an unpaid booking does not
 * live long enough to come near a kickoff anyway (its seat hold lapses within minutes).
 */
public record CancellationWindow(Instant kickoff, Duration cutoff) {

    public CancellationWindow {
        Objects.requireNonNull(kickoff, "kickoff must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        if (cutoff.isNegative()) {
            throw new IllegalArgumentException("cutoff must not be negative: " + cutoff);
        }
    }

    /** The last moment a paid ticket can still be cancelled — exclusive. */
    public Instant closesAt() {
        return kickoff.minus(cutoff);
    }

    public boolean isOpenAt(Instant now) {
        return now.isBefore(closesAt());
    }

    public void requireOpenAt(Instant now) {
        if (!isOpenAt(now)) {
            throw new CancellationWindowClosedException(closesAt(), cutoff);
        }
    }
}
