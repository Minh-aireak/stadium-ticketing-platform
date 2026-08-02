package com.aireak.catalog.application.port.in;

import com.aireak.catalog.domain.model.MatchStatus;

import java.time.Instant;
import java.util.Optional;

public interface GetShowtimeUseCase {
    Optional<ShowtimeDetails> getShowtime(String showtimeId);

    record ShowtimeDetails(String showtimeId, Instant startTime, MatchStatus matchStatus) {
    }
}
