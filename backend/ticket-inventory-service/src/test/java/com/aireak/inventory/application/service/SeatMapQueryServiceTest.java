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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatMapQueryServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");
    private static final String CALLER = "customer-1";
    private static final String OTHER_CUSTOMER = "customer-2";

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

        assertThat(service.getSeatMap("missing", CALLER)).isEmpty();
    }

    @Test
    void reportsSoldRegardlessOfRedisHold() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        Seat sold = new Seat(a1, SeatStatus.SOLD, "booking-1", SeatTier.VIP, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(sold));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));
        when(seatHoldPort.findHoldOwners("showtime-1", List.of(a1))).thenReturn(Map.of());

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1", CALLER).orElseThrow();

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
        when(seatHoldPort.findHoldOwners("showtime-1", List.of(a1))).thenReturn(Map.of(a1, OTHER_CUSTOMER));

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1", CALLER).orElseThrow();

        assertThat(result.seats().get(0).status()).isEqualTo("HELD");
    }

    @Test
    void reportsAvailableWhenNeitherSoldNorHeld() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        Seat available = new Seat(a1, SeatTier.STANDARD, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(available));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));
        when(seatHoldPort.findHoldOwners("showtime-1", List.of(a1))).thenReturn(Map.of());

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1", CALLER).orElseThrow();

        GetSeatMapUseCase.SeatSummary seat = result.seats().get(0);
        assertThat(seat.status()).isEqualTo("AVAILABLE");
        assertThat(seat.tier()).isEqualTo("STANDARD");
        assertThat(seat.price()).isEqualByComparingTo(PRICE);
        assertThat(seat.seatCode()).isEqualTo("A1");
    }

    @Test
    void batchesTheRedisHoldLookupIntoASingleCallForTheWholeSeatMap() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        SeatCode a2 = new SeatCode("A2");
        Seat seat1 = new Seat(a1, SeatTier.STANDARD, PRICE);
        Seat seat2 = new Seat(a2, SeatTier.STANDARD, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(seat1, seat2));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));
        when(seatHoldPort.findHoldOwners("showtime-1", List.of(a1, a2))).thenReturn(Map.of(a2, OTHER_CUSTOMER));

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1", CALLER).orElseThrow();

        assertThat(result.seats()).hasSize(2);
        assertThat(result.seats().get(0).status()).isEqualTo("AVAILABLE");
        assertThat(result.seats().get(1).status()).isEqualTo("HELD");
        verify(seatHoldPort, times(1)).findHoldOwners(eq("showtime-1"), any());
    }

    /**
     * The seat map used to report every hold identically, so a customer who reloaded seat
     * selection — or backed into it out of checkout, which deliberately keeps the holds — got
     * their own seats back as another shopper's: greyed out, unselectable, and stuck that way
     * for the rest of the 10-minute TTL.
     */
    @Test
    void marksAHeldSeatAsTheCallersOwnWhenTheCallerHoldsIt() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        Seat available = new Seat(a1, SeatTier.VIP, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(available));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));
        when(seatHoldPort.findHoldOwners("showtime-1", List.of(a1))).thenReturn(Map.of(a1, CALLER));

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1", CALLER).orElseThrow();

        GetSeatMapUseCase.SeatSummary seat = result.seats().get(0);
        assertThat(seat.status()).isEqualTo("HELD");
        assertThat(seat.heldByCaller()).isTrue();
    }

    @Test
    void doesNotMarkAnotherCustomersHoldAsTheCallersOwn() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        Seat available = new Seat(a1, SeatTier.VIP, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(available));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));
        when(seatHoldPort.findHoldOwners("showtime-1", List.of(a1))).thenReturn(Map.of(a1, OTHER_CUSTOMER));

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1", CALLER).orElseThrow();

        GetSeatMapUseCase.SeatSummary seat = result.seats().get(0);
        assertThat(seat.status()).isEqualTo("HELD");
        assertThat(seat.heldByCaller()).isFalse();
    }

    // A sale is the final word. If a stale hold entry still named the caller, saying the seat is
    // theirs would hand the storefront a sold seat dressed up as reselectable.
    @Test
    void neverMarksASoldSeatAsTheCallersOwn() {
        newService();
        SeatCode a1 = new SeatCode("A1");
        Seat sold = new Seat(a1, SeatStatus.SOLD, "booking-1", SeatTier.VIP, PRICE);
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(sold));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));
        when(seatHoldPort.findHoldOwners("showtime-1", List.of(a1))).thenReturn(Map.of(a1, CALLER));

        GetSeatMapUseCase.SeatMapResult result = service.getSeatMap("showtime-1", CALLER).orElseThrow();

        GetSeatMapUseCase.SeatSummary seat = result.seats().get(0);
        assertThat(seat.status()).isEqualTo("SOLD");
        assertThat(seat.heldByCaller()).isFalse();
    }
}
