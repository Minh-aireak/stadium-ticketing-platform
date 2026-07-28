package com.aireak.catalog.adapter.in.web;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/** Inbound REST adapter: match catalog management endpoints. */
@RestController
@RequestMapping("/api/v1/matches")
@RequiredArgsConstructor
public class MatchController {

    private final CreateMatchUseCase createMatchUseCase;
    private final AddShowtimeUseCase addShowtimeUseCase;
    private final PublishMatchUseCase publishMatchUseCase;

    /** POST /api/v1/matches */
    @PostMapping
    public ResponseEntity<CreateMatchResponse> create(@Valid @RequestBody CreateMatchRequest req) {
        String matchId = createMatchUseCase.createMatch(req.homeTeam(), req.awayTeam(), req.competition());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateMatchResponse(matchId));
    }

    /** POST /api/v1/matches/{matchId}/showtimes */
    @PostMapping("/{matchId}/showtimes")
    public ResponseEntity<Void> addShowtime(@PathVariable String matchId,
                                            @Valid @RequestBody AddShowtimeRequest req) {
        addShowtimeUseCase.addShowtime(matchId, req.startTime(), req.venueId(), req.totalSeats());
        return ResponseEntity.ok().build();
    }

    /** PUT /api/v1/matches/{matchId}/publish */
    @PutMapping("/{matchId}/publish")
    public ResponseEntity<Void> publish(@PathVariable String matchId) {
        publishMatchUseCase.publishMatch(matchId);
        return ResponseEntity.ok().build();
    }

    record CreateMatchRequest(@NotBlank String homeTeam, @NotBlank String awayTeam,
                              @NotBlank String competition) {}
    record CreateMatchResponse(String matchId) {}
    record AddShowtimeRequest(Instant startTime, @NotBlank String venueId, int totalSeats) {}
}
