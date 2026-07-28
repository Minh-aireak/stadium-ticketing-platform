package com.aireak.catalog.application.port.in;

import com.aireak.catalog.domain.model.Match;

import java.util.List;

/** Public catalog browse/search — published matches only. */
public interface ListMatchesUseCase {

    /**
     * @param query free-text search (matches home/away team or competition); blank/null lists
     *              all published matches instead of searching
     * @param page  0-based page index, ignored when {@code query} is non-blank
     * @param size  page size, ignored when {@code query} is non-blank
     */
    MatchPage listMatches(String query, int page, int size);

    record MatchPage(List<Match> items, long totalElements, int page, int size) {}
}
