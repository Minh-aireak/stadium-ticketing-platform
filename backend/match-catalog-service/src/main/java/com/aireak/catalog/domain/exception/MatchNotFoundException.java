package com.aireak.catalog.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when a Match for the given id is not found. */
public class MatchNotFoundException extends DomainException {
    public MatchNotFoundException(String matchId) {
        super("Match not found: " + matchId);
    }
}
