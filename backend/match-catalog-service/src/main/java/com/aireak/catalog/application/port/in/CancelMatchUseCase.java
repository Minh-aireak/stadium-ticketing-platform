package com.aireak.catalog.application.port.in;

/** Inbound port: cancel a match — ADMIN only. Triggers the booking/refund/notification flow. */
public interface CancelMatchUseCase {
    void cancelMatch(String matchId, String reason);
}
