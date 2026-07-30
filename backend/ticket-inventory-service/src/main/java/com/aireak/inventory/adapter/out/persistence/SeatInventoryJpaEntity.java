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
 * <p>@Version provides optimistic lock (layer 2 of concurrency protection;
 * layer 1 is Redisson in the application service).
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

    @Version
    @Column(name = "version")
    private Long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "showtime_id", referencedColumnName = "showtime_id",
                foreignKey = @ForeignKey(name = "fk_seats_showtime"))
    @Builder.Default
    private List<SeatJpaEntity> seats = new ArrayList<>();
}
