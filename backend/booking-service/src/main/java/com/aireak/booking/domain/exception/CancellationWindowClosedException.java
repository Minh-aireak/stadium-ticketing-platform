package com.aireak.booking.domain.exception;

import com.aireak.common.exception.DomainException;

import java.time.Duration;
import java.time.Instant;

/** A paid ticket's cancellation deadline — a fixed time before kickoff — has passed. */
public class CancellationWindowClosedException extends DomainException {

    private final Instant closedAt;

    public CancellationWindowClosedException(Instant closedAt, Duration cutoff) {
        super("Paid tickets can only be cancelled until " + closedAt + ", " + cutoff.toHours()
                + " hours before kickoff");
        this.closedAt = closedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
