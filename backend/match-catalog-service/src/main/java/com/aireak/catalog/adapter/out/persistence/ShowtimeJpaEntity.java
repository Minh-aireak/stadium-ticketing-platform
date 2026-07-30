package com.aireak.catalog.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "showtimes",
        indexes = @Index(name = "idx_showtimes_match", columnList = "match_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_showtimes_venue_time",
                columnNames = {"venue_id", "start_time"}))
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

    @Column(name = "base_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "currency", nullable = false, length = 3)
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    private String currency;
}
