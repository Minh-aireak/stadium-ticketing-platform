package com.aireak.catalog.application.port.in;

/** Inbound port: publish a match — makes it visible to customers and indexes it for search. */
public interface PublishMatchUseCase {
    void publishMatch(String matchId);
}
