package com.aireak.inventory.adapter.out.persistence;

import com.aireak.common.persistence.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity for SeatInventory aggregate root.
 * One row per showtime. Seats are owned as @OneToMany.
 *
 * <p>Carries no {@code @Version}. It used to, described as "layer 2" behind the Redisson lock —
 * but this row has no mutable column at all: it is inserted once when the seat map is generated
 * and never updated, and selling a seat changes a child {@code SeatJpaEntity} row, which JPA does
 * not count as a change to this entity. The version therefore never incremented on the only path
 * that writes, so it protected nothing (see SeatInventoryOptimisticLockIntegrationTest, which
 * pinned that before it was removed). The real protection is the Redisson per-showtime lock plus
 * {@code Seat#sell} rejecting a seat already SOLD by a different booking.
 */
@Getter
@Setter
@Entity
@Table(name = "seat_inventories")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatInventoryJpaEntity extends BaseAuditEntity {

    @Id
    @Column(name = "showtime_id", nullable = false, length = 36)
    private String showtimeId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "showtime_id", referencedColumnName = "showtime_id",
                foreignKey = @ForeignKey(name = "fk_seats_showtime"))
    @Builder.Default
    private List<SeatJpaEntity> seats = new ArrayList<>();
}
