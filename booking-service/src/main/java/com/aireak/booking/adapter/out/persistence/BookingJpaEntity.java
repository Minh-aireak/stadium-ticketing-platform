package com.aireak.booking.adapter.out.persistence;

import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.common.persistence.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for Booking persistence.
 * Seat codes stored as comma-separated string for simplicity.
 * Use @ElementCollection for normalized storage if seat history matters.
 */
@Getter
@Setter
@Entity
@Table(name = "bookings")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingJpaEntity extends BaseAuditEntity {

    @Id
    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "showtime_id", nullable = false, length = 36)
    private String showtimeId;

    /** Comma-separated seat codes: "A1,A2,B3" */
    @Column(name = "seat_codes", nullable = false, length = 1000)
    private String seatCodes;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version")
    private Long version;
}
