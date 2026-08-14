package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.application.port.out.ShowtimeCatalogPort;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatMapLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatMapGenerationServiceTest {

    private static final BigDecimal BASE_PRICE = new BigDecimal("150000");
    private static final Instant START_TIME = Instant.parse("2026-01-01T18:00:00Z");

    @Mock
    private SeatInventoryRepository seatInventoryRepository;
    @Mock
    private ShowtimeCatalogPort showtimeCatalogPort;

    private SeatMapGenerationService service;

    private void newService() {
        service = new SeatMapGenerationService(seatInventoryRepository, showtimeCatalogPort);
    }

    @Test
    void generatesAndSavesSeatMapWhenNoneExistsYet() {
        newService();
        when(seatInventoryRepository.existsByShowtimeId("showtime-1")).thenReturn(false);

        service.generate("showtime-1", SeatMapLayout.MY_DINH, START_TIME, 432, BASE_PRICE);

        ArgumentCaptor<SeatInventory> saved = ArgumentCaptor.forClass(SeatInventory.class);
        verify(seatInventoryRepository).save(saved.capture());
        assertThat(saved.getValue().getShowtimeId()).isEqualTo("showtime-1");
        assertThat(saved.getValue().getSeats()).hasSize(432);
        verify(showtimeCatalogPort).rememberStartTime("showtime-1", START_TIME);
    }

    @Test
    void skipsGenerationWhenSeatMapAlreadyExists_idempotentOnReplay() {
        newService();
        when(seatInventoryRepository.existsByShowtimeId("showtime-1")).thenReturn(true);

        service.generate("showtime-1", SeatMapLayout.MY_DINH, START_TIME, 432, BASE_PRICE);

        verify(seatInventoryRepository, never()).save(any());
    }

    /**
     * The start-time cache must still be repopulated on a replay — this pod's local cache may be
     * cold (e.g. after a restart), and a redelivered {@code ShowtimeAddedEvent} is the only chance
     * to refill it, since it's the only event that carries the showtime's start time.
     */
    @Test
    void stillRemembersStartTimeOnAnIdempotentReplay() {
        newService();
        when(seatInventoryRepository.existsByShowtimeId("showtime-1")).thenReturn(true);

        service.generate("showtime-1", SeatMapLayout.MY_DINH, START_TIME, 432, BASE_PRICE);

        verify(showtimeCatalogPort).rememberStartTime("showtime-1", START_TIME);
    }
}
