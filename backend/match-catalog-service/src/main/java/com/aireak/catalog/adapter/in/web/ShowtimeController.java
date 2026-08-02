package com.aireak.catalog.adapter.in.web;

import com.aireak.catalog.application.port.in.GetShowtimeUseCase;
import com.aireak.catalog.domain.model.MatchStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/showtimes")
@RequiredArgsConstructor
public class ShowtimeController {

    private final GetShowtimeUseCase getShowtimeUseCase;

    @GetMapping("/{showtimeId}")
    public ResponseEntity<ShowtimeAvailabilityResponse> get(@PathVariable String showtimeId) {
        return getShowtimeUseCase.getShowtime(showtimeId)
                .map(showtime -> ResponseEntity.ok(new ShowtimeAvailabilityResponse(
                        showtime.showtimeId(), showtime.startTime(),
                        showtime.matchStatus() == MatchStatus.PUBLISHED
                                && showtime.startTime().isAfter(Instant.now()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record ShowtimeAvailabilityResponse(String showtimeId, Instant startTime, boolean bookable) {
    }
}
