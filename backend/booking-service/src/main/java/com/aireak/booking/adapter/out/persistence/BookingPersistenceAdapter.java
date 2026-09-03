package com.aireak.booking.adapter.out.persistence;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.SeatSelection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BookingPersistenceAdapter implements BookingRepository {

    private final BookingJpaRepository jpaRepository;

    @Override
    public void save(Booking booking) {
        jpaRepository.save(toJpaEntity(booking));
    }

    @Override
    public Optional<Booking> findById(String bookingId) {
        return jpaRepository.findById(bookingId).map(this::toDomain);
    }

    @Override
    public Optional<Booking> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(this::toDomain);
    }

    @Override
    public List<Booking> findConfirmedAwaitingInventoryConfirmation(Instant updatedBefore, int limit) {
        return jpaRepository
                .findByStatusAndInventoryConfirmedFalseAndInventorySaleRefusedFalseAndUpdatedAtBefore(
                        BookingStatus.CONFIRMED, updatedBefore, Limit.of(limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Booking> findPendingPaymentOlderThan(Instant updatedBefore, int limit) {
        return jpaRepository
                .findByStatusAndUpdatedAtBefore(BookingStatus.PENDING_PAYMENT, updatedBefore, Limit.of(limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Booking> findDraftOlderThan(Instant updatedBefore, int limit) {
        return jpaRepository
                .findByStatusAndUpdatedAtBefore(BookingStatus.DRAFT, updatedBefore, Limit.of(limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countInventorySaleRefused() {
        return jpaRepository.countByInventorySaleRefusedTrue();
    }

    @Override
    public List<Booking> findByCustomerId(String customerId, int page, int size) {
        return jpaRepository
                .findByCustomerId(customerId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toDomain)
                .getContent();
    }

    @Override
    public long countByCustomerId(String customerId) {
        return jpaRepository.countByCustomerId(customerId);
    }

    @Override
    public List<Booking> findActiveByShowtimeId(String showtimeId) {
        return jpaRepository.findByShowtimeIdAndStatusNot(showtimeId, BookingStatus.CANCELLED)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private BookingJpaEntity toJpaEntity(Booking booking) {
        return BookingJpaEntity.builder()
                .bookingId(booking.getBookingId())
                .customerId(booking.getCustomerId())
                .customerEmail(booking.getCustomerEmail())
                .showtimeId(booking.getShowtimeId())
                .seatCodes(String.join(",", booking.getSeatSelection().seatCodes()))
                .amount(booking.getAmount().amount())
                .currency(booking.getAmount().currency())
                .status(booking.getStatus())
                .idempotencyKey(booking.getIdempotencyKey())
                .inventoryConfirmed(booking.isInventoryConfirmed())
                .inventorySaleRefused(booking.isInventorySaleRefused())
                .version(booking.getVersion())
                .build();
    }

    private Booking toDomain(BookingJpaEntity entity) {
        List<String> seatCodes = Arrays.asList(entity.getSeatCodes().split(","));
        return Booking.reconstitute(
                entity.getBookingId(),
                entity.getCustomerId(),
                entity.getCustomerEmail(),
                entity.getShowtimeId(),
                new SeatSelection(seatCodes),
                BookingAmount.of(entity.getAmount(), entity.getCurrency()),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getIdempotencyKey(),
                entity.getVersion(),
                entity.isInventoryConfirmed(),
                entity.isInventorySaleRefused()
        );
    }
}
