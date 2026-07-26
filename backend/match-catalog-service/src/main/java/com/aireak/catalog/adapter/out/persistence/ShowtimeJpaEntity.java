package com.aireak.catalog.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "showtimes",
        indexes = @Index(name = "idx_showtimes_match", columnList = "match_id"))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowtimeJpaEntity {

    @Id
    @Column(name = "showtime_id", nullable = false, length = 36)
    private String showtimeId;

    @Column(name = "match_id", nullable = false, length = 36)
    private String matchId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "venue_id", nullable = false, length = 36)
    private String venueId;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "available_seats", nullable = false)
    private int availableSeats;
}
