package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import com.aireak.inventory.domain.exception.SeatInventoryNotFoundException;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatSaleConfirmerTest {

    private static final SeatCode A1 = new SeatCode("A1");

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
        SeatInventory inventory = SeatInventory.create("showtime-1", List.of(A1));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));

        confirmer.confirmSale("showtime-1", List.of(A1), "booking-1");

        ArgumentCaptor<SeatInventory> saved = ArgumentCaptor.forClass(SeatInventory.class);
        verify(seatInventoryRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(inventory);

        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(SeatsSoldEvent.class);
    }

    @Test
    void confirmSaleThrowsWhenInventoryNotFound() {
        newConfirmer();
        when(seatInventoryRepository.findByShowtimeId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> confirmer.confirmSale("missing", List.of(A1), "booking-1"))
                .isInstanceOf(SeatInventoryNotFoundException.class);
    }
}
