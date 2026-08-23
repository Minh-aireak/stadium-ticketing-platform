package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import com.aireak.inventory.domain.exception.SeatInventoryNotFoundException;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatSaleConfirmerTest {

    private static final SeatCode A1 = new SeatCode("A1");
    private static final BigDecimal PRICE = new BigDecimal("150000");

    @Mock
    private SeatInventoryRepository seatInventoryRepository;
    @Mock
    private DomainEventPublisher eventPublisher;

    private SeatSaleConfirmer confirmer;

    private void newConfirmer() {
        confirmer = new SeatSaleConfirmer(seatInventoryRepository, eventPublisher);
    }

    @Test
    void confirmSaleSellsSeatsSavesAndPublishesEvent() {
        newConfirmer();
        SeatInventory inventory = SeatInventory.create("showtime-1",
                List.of(new Seat(A1, SeatTier.STANDARD, PRICE)));
        when(seatInventoryRepository.findByShowtimeIdWithSeats("showtime-1", List.of(A1)))
                .thenReturn(Optional.of(inventory));

        confirmer.confirmSale("showtime-1", List.of(A1), "booking-1");

        // saveSeats, not save: the confirm path writes the seats it sold and never the aggregate
        // root, whose row has nothing to update.
        ArgumentCaptor<SeatInventory> saved = ArgumentCaptor.forClass(SeatInventory.class);
        verify(seatInventoryRepository).saveSeats(saved.capture());
        assertThat(saved.getValue()).isSameAs(inventory);

        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(SeatsSoldEvent.class);
    }

    @Test
    void confirmSaleThrowsWhenInventoryNotFound() {
        newConfirmer();
        when(seatInventoryRepository.findByShowtimeIdWithSeats("missing", List.of(A1)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> confirmer.confirmSale("missing", List.of(A1), "booking-1"))
                .isInstanceOf(SeatInventoryNotFoundException.class);
    }
}
