package com.aireak.catalog.application.port.out;

/**
 * Unchecked wrapper for I/O failures talking to the Elasticsearch cluster.
 *
 * <p>Part of {@link MatchSearchPort}'s contract rather than the Elasticsearch adapter's, because
 * it escapes through that port to whoever called it: {@code MatchController} has to map it to a
 * status, and a web adapter reaching into {@code adapter.out.search} to name it would couple the
 * two adapters to each other. Same move, and the same reason, as {@code PaymentDeclinedException}
 * in payment-service.
 */
public class MatchSearchException extends RuntimeException {
    public MatchSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
