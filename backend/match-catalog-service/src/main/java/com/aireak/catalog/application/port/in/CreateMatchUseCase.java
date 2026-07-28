package com.aireak.catalog.application.port.in;

/** Inbound port: create a new match in the catalog. */
public interface CreateMatchUseCase {
    /** @return the generated matchId */
    String createMatch(String homeTeam, String awayTeam, String competition);
}
