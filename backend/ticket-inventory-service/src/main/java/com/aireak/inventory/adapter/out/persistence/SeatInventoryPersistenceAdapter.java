package com.aireak.inventory.adapter.out.persistence;

import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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
    private final SeatJpaRepository seatJpaRepository;

    /**
     * If a row for this showtime already exists, mutate the SAME managed entity
     * (and its managed child {@code SeatJpaEntity} rows) in place rather than
     * building a brand-new detached instance. A freshly-{@code .builder()}'d
     * entity always has {@code version=null}, which Hibernate treats as
     * transient — {@code jpaRepository.save()} on it would call {@code persist()}
     * even though a managed instance for the same id already exists in this
     * persistence context (e.g. loaded earlier in the same transaction by
     * {@code findByShowtimeId()}), throwing {@code NonUniqueObjectException}.
     */
    @Override
    public void save(SeatInventory seatInventory) {
        SeatInventoryJpaEntity entity = jpaRepository.findByShowtimeId(seatInventory.getShowtimeId())
                .map(existing -> updateSeats(existing, seatInventory))
                .orElseGet(() -> toJpaEntity(seatInventory));
        jpaRepository.save(entity);
    }

    private SeatInventoryJpaEntity updateSeats(SeatInventoryJpaEntity entity, SeatInventory agg) {
        Map<String, SeatJpaEntity> bySeatCode = entity.getSeats().stream()
                .collect(Collectors.toMap(SeatJpaEntity::getSeatCode, s -> s));
        agg.getSeats().forEach(seat -> {
            SeatJpaEntity existing = bySeatCode.get(seat.getSeatCode().value());
            if (existing != null) {
                existing.setStatus(seat.getStatus());
                existing.setReservedByBookingId(seat.getReservedByBookingId());
            }
        });
        return entity;
    }

    @Override
    public Optional<SeatInventory> findByShowtimeId(String showtimeId) {
        return jpaRepository.findByShowtimeId(showtimeId).map(this::toDomain);
    }

    @Override
    public boolean existsByShowtimeId(String showtimeId) {
        return jpaRepository.existsByShowtimeId(showtimeId);
    }

    @Override
    public List<SeatCode> findSoldSeatCodes(String showtimeId, List<SeatCode> seatCodes) {
        List<String> codes = seatCodes.stream().map(SeatCode::value).toList();
        return seatJpaRepository.findByShowtimeIdAndSeatCodeInAndStatus(showtimeId, codes, SeatStatus.SOLD)
                .stream()
                .map(s -> new SeatCode(s.getSeatCode()))
                .toList();
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
