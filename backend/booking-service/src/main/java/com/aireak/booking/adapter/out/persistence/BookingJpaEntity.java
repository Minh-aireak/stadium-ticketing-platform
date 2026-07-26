package com.aireak.booking.adapter.out.persistence;

import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.common.persistence.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

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

    @Column(name = "seat_codes", nullable = false, length = 1000)
    private String seatCodes;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BookingStatus status;

    @Column(name = "idempotency_key", length = 255, updatable = false)
    private String idempotencyKey;

    @Column(name = "inventory_confirmed", nullable = false)
    private boolean inventoryConfirmed;

    @Version
    @Column(name = "version")
    private Long version;
}
