package com.aireak.catalog.application.port.in;

import com.aireak.catalog.domain.model.Match;

import java.util.Optional;

/** Public catalog detail lookup — never exposes a DRAFT (unpublished) match. */
public interface GetMatchUseCase {
    Optional<Match> getMatch(String matchId);
}
