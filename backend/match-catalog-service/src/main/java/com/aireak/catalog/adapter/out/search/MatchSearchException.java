package com.aireak.catalog.adapter.out.search;

/** Unchecked wrapper for I/O failures talking to the Elasticsearch cluster. */
public class MatchSearchException extends RuntimeException {
    public MatchSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
