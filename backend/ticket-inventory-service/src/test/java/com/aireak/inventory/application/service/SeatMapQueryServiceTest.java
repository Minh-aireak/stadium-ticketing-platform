package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.out.SeatHoldPort;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatStatus;
import com.aireak.inventory.domain.model.SeatTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatMapQueryServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    @Mock
    private SeatInventoryRepository seatInventoryRepository;
    @Mock
    private SeatHoldPort seatHoldPort;

    private SeatMapQueryService service;

    private void newService() {
        service = new SeatMapQueryService(seatInventoryRepository, seatHoldPort);
    }

    @Test
    void returnsEmptyWhenInventoryNotFound() {
        newService();
        when(seatInventoryRepository.findByShowtimeId("missing")).thenReturn(Optional.empty());

        assertThat(service.getSeatMap("missing")).isEmpty();
    }

    @Test
    void reportsSoldRegardlessOfRedisHold() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        Seat sold = new Seat(a1, SeatStatus.SOLD, "booking-1", SeatTier.VIP, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(sold));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1").orElseThrow();

        assertThat(result.seats()).hasSize(1);
        assertThat(result.seats().get(0).status()).isEqualTo("SOLD");
    }

    @Test
    void reportsHeldWhenAvailableInDbButHeldInRedis() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        Seat available = new Seat(a1, SeatTier.VIP, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(available));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));
        when(seatHoldPort.isHeld("showtime-1", a1)).thenReturn(true);

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1").orElseThrow();

        assertThat(result.seats().get(0).status()).isEqualTo("HELD");
    }

    @Test
    void reportsAvailableWhenNeitherSoldNorHeld() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        Seat available = new Seat(a1, SeatTier.STANDARD, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(available));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));
        when(seatHoldPort.isHeld("showtime-1", a1)).thenReturn(false);

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1").orElseThrow();

        GetSeatMapUseCase.SeatSummary seat = result.seats().get(0);
        assertThat(seat.status()).isEqualTo("AVAILABLE");
        assertThat(seat.tier()).isEqualTo("STANDARD");
        assertThat(seat.price()).isEqualByComparingTo(PRICE);
        assertThat(seat.seatCode()).isEqualTo("A1");
    }
}
