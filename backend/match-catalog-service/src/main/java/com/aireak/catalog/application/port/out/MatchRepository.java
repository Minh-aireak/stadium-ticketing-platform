package com.aireak.catalog.application.port.out;

import com.aireak.catalog.domain.model.Match;

import java.util.Optional;

public interface MatchRepository {
    void save(Match match);
    Optional<Match> findById(String matchId);
}
