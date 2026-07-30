package com.aireak.catalog.application.port.in;

/** Inbound port: mark a match as completed (final whistle) — ADMIN only. */
public interface CompleteMatchUseCase {
    void completeMatch(String matchId);
}
