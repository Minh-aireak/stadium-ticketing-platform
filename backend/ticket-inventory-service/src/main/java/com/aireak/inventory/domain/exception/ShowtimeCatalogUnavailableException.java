package com.aireak.inventory.domain.exception;

/**
 * match-catalog-service could not be reached, or could not be made to answer, while checking
 * whether a showtime is still bookable.
 *
 * <p>Deliberately NOT a {@code DomainException}. A DomainException is a statement about the
 * caller's request, and {@code GlobalExceptionHandler} maps every one of them to 422 — which is
 * where this used to land, as a second flavour of {@link ShowtimeBookingClosedException}
 * distinguished only by its message. Three things were wrong with that at once: the status blamed
 * the customer for an outage; the customer was told ticket sales had closed for a match that was
 * still on sale; and the frontend only attaches a correlation id to a 5xx, so the single failure
 * that actually needs a Kibana lookup was the one that arrived without an id.
 *
 * <p>Both still fail closed — an unanswerable "is this bookable?" must never read as yes. The
 * split changes only what is said about it, and mirrors identity-service's
 * {@code RefreshSessionStoreUnavailableException}: an infrastructure failure kept out of the
 * domain hierarchy precisely so it cannot inherit a domain status.
 *
 * <p>Mapped to 503 by {@code SeatInventoryController#handleCatalogUnavailable}.
 */
public class ShowtimeCatalogUnavailableException extends RuntimeException {

    public ShowtimeCatalogUnavailableException(String showtimeId, Throwable cause) {
        super("Unable to verify the booking window for showtime: " + showtimeId, cause);
    }
}
