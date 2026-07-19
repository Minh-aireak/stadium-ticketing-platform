package com.aireak.inventory.domain.model;

/** Status of an individual seat within a showtime inventory. */
public enum SeatStatus {
    AVAILABLE,
    RESERVED,   // held pending payment — released on timeout or payment failure
    SOLD        // payment confirmed
}
