package com.aireak.catalog.domain.model;

/** Status of a Match in the catalog. */
public enum MatchStatus {
    DRAFT,      // created by admin, not yet published
    PUBLISHED,  // visible to customers, tickets can be sold
    COMPLETED,  // match has taken place
    CANCELLED   // match cancelled
}
