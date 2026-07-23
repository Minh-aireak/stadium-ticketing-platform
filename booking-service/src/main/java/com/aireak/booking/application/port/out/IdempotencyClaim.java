package com.aireak.booking.application.port.out;

// Outcome of {@link IdempotencyStore#claim(String)}.
public sealed interface IdempotencyClaim {

    // No one else holds this key — caller may proceed with the operation.
    record Claimed() implements IdempotencyClaim {}

    // Another request with the same key is currently being processed.
    record InProgress() implements IdempotencyClaim {}

    // A prior request with the same key already finished successfully.
    record Completed(String bookingId) implements IdempotencyClaim {}
}
