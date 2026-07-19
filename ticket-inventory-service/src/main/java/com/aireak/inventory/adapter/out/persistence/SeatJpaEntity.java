package com.aireak.inventory.adapter.out.persistence;

import com.aireak.inventory.domain.model.SeatStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * JPA entity for Seat — owned by SeatInventoryJpaEntity.
 * Mapped as @ElementCollection equivalent via @OneToMany.
 */
@Getter
@Setter
@Entity
@Table(name = "seats",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_seats_showtime_code",
                columnNames = {"showtime_id", "seat_code"}))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seat_seq")
    @SequenceGenerator(name = "seat_seq", sequenceName = "seat_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "showtime_id", nullable = false, length = 36)
    private String showtimeId;

    @Column(name = "seat_code", nullable = false, length = 10)
    private String seatCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SeatStatus status;

    @Column(name = "reserved_by_booking_id", length = 36)
    private String reservedByBookingId;
}
