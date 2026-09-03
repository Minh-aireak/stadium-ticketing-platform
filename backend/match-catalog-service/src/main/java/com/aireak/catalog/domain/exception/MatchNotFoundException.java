package com.aireak.catalog.domain.exception;

import com.aireak.common.exception.ResourceNotFoundException;

/**
 * Thrown when a Match for the given id is not found → 404, matching what
 * {@code MatchController#get} has always answered for the same missing match.
 */
public class MatchNotFoundException extends ResourceNotFoundException {
    public MatchNotFoundException(String matchId) {
        super("Match not found: " + matchId);
    }
}
