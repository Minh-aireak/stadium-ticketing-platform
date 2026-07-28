package com.aireak.catalog.application.port.out;

import com.aireak.catalog.domain.model.Match;
import com.aireak.catalog.domain.model.MatchStatus;

import java.util.List;
import java.util.Optional;

public interface MatchRepository {
    void save(Match match);
    Optional<Match> findById(String matchId);

    /** Page of matches in the given status, newest first. */
    List<Match> findByStatus(MatchStatus status, int page, int size);
    long countByStatus(MatchStatus status);
}
