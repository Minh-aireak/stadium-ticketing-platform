package com.aireak.inventory.adapter.out.persistence;

import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Persistence adapter: maps SeatInventory aggregate ↔ JPA entities.
 *
 * <p>Mapping strategy:
 * <ul>
 *   <li>SeatInventory → SeatInventoryJpaEntity (1:1 by showtimeId)</li>
 *   <li>Seat → SeatJpaEntity (1:N owned by SeatInventory)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SeatInventoryPersistenceAdapter implements SeatInventoryRepository {

    private final SeatInventoryJpaRepository jpaRepository;

    @Override
    public void save(SeatInventory seatInventory) {
        SeatInventoryJpaEntity entity = toJpaEntity(seatInventory);
        jpaRepository.save(entity);
    }

    @Override
    public Optional<SeatInventory> findByShowtimeId(String showtimeId) {
        return jpaRepository.findByShowtimeId(showtimeId).map(this::toDomain);
    }

    // ----------------------------------------------------------------
    // Domain → JPA
    // ----------------------------------------------------------------

    private SeatInventoryJpaEntity toJpaEntity(SeatInventory agg) {
        List<SeatJpaEntity> seatEntities = agg.getSeats().stream()
                .map(s -> SeatJpaEntity.builder()
                        .showtimeId(agg.getShowtimeId())
                        .seatCode(s.getSeatCode().value())
                        .status(s.getStatus())
                        .reservedByBookingId(s.getReservedByBookingId())
                        .build())
                .collect(Collectors.toList());

        SeatInventoryJpaEntity entity = SeatInventoryJpaEntity.builder()
                .showtimeId(agg.getShowtimeId())
                .build();
        entity.getSeats().clear();
        entity.getSeats().addAll(seatEntities);
        return entity;
    }

    // ----------------------------------------------------------------
    // JPA → Domain (reconstitute)
    // ----------------------------------------------------------------

    private SeatInventory toDomain(SeatInventoryJpaEntity entity) {
        List<Seat> seats = entity.getSeats().stream()
                .map(s -> new Seat(
                        new SeatCode(s.getSeatCode()),
                        s.getStatus(),
                        s.getReservedByBookingId()))
                .toList();
        return SeatInventory.reconstitute(entity.getShowtimeId(), seats);
    }
}
