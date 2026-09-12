package com.aireak.catalog.application.port.in;

/**
 * Inbound port: make the search index say what the database says about one match.
 *
 * <p>Driven by {@code MatchSearchIndexConsumer} off this service's own match lifecycle events.
 * The event is only the trigger — the match is re-read from the database and the index is
 * written from that, so the outcome is the same whichever order the events arrive in and however
 * many times one is delivered. It either converges the index, or fails loudly and lets Kafka
 * redeliver.
 */
public interface ReconcileMatchSearchIndexUseCase {

    void reconcile(String matchId);
}
