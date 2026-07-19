package com.aireak.booking.adapter.out.persistence;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.SeatSelection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Persistence adapter: maps Booking domain aggregate ↔ JPA entity.
 * Seat codes stored as comma-separated string in a single column.
 */
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

    private BookingJpaEntity toJpaEntity(Booking booking) {
        return BookingJpaEntity.builder()
                .bookingId(booking.getBookingId())
                .customerId(booking.getCustomerId())
                .showtimeId(booking.getShowtimeId())
                .seatCodes(String.join(",", booking.getSeatSelection().seatCodes()))
                .amount(booking.getAmount().amount())
                .currency(booking.getAmount().currency())
                .status(booking.getStatus())
                .createdAt(booking.getCreatedAt())
                .build();
    }

    private Booking toDomain(BookingJpaEntity entity) {
        List<String> seatCodes = Arrays.asList(entity.getSeatCodes().split(","));
        return Booking.reconstitute(
                entity.getBookingId(),
                entity.getCustomerId(),
                entity.getShowtimeId(),
                new SeatSelection(seatCodes),
                BookingAmount.of(entity.getAmount(), entity.getCurrency()),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }
}
