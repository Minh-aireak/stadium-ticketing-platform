package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.GetSeatingLayoutUseCase;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
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
class SeatingLayoutQueryServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    @Mock
    private SeatInventoryRepository seatInventoryRepository;

    private SeatingLayoutQueryService service;

    private void newService() {
        service = new SeatingLayoutQueryService(seatInventoryRepository);
    }

    @Test
    void returnsEmptyWhenInventoryNotFound() {
        newService();
        when(seatInventoryRepository.findByShowtimeId("missing")).thenReturn(Optional.empty());

        assertThat(service.getLayout("missing")).isEmpty();
    }

    @Test
    void groupsSeatsByTierThenRow() {
        newService();
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(
                new Seat(new SeatCode("A2"), SeatTier.VIP, PRICE),
                new Seat(new SeatCode("A1"), SeatTier.VIP, PRICE),
                new Seat(new SeatCode("C1"), SeatTier.STANDARD, PRICE)
        ));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));

        GetSeatingLayoutUseCase.LayoutResult result = service.getLayout("showtime-1").orElseThrow();

        assertThat(result.showtimeId()).isEqualTo("showtime-1");
        assertThat(result.sections()).hasSize(2);

        GetSeatingLayoutUseCase.SectionSummary vip = result.sections().get(0);
        assertThat(vip.tier()).isEqualTo("VIP");
        assertThat(vip.blocks()).hasSize(1);
        assertThat(vip.blocks().get(0).row()).isEqualTo("A");
        // seatCodes come back sorted by seat number even though the source order was A2, A1.
        assertThat(vip.blocks().get(0).seatCodes()).containsExactly("A1", "A2");

        GetSeatingLayoutUseCase.SectionSummary standard = result.sections().get(1);
        assertThat(standard.tier()).isEqualTo("STANDARD");
        assertThat(standard.blocks().get(0).row()).isEqualTo("C");
        assertThat(standard.blocks().get(0).seatCodes()).containsExactly("C1");
    }

    @Test
    void ordersSectionsVipThenPremiumThenStandardRegardlessOfSeatOrder() {
        newService();
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(
                new Seat(new SeatCode("E1"), SeatTier.STANDARD, PRICE),
                new Seat(new SeatCode("C1"), SeatTier.PREMIUM, PRICE),
                new Seat(new SeatCode("A1"), SeatTier.VIP, PRICE)
        ));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));

        GetSeatingLayoutUseCase.LayoutResult result = service.getLayout("showtime-1").orElseThrow();

        assertThat(result.sections()).extracting(GetSeatingLayoutUseCase.SectionSummary::tier)
                .containsExactly("VIP", "PREMIUM", "STANDARD");
    }

    @Test
    void rowsWithinASectionAreOrderedAlphabetically() {
        newService();
        SeatInventory inventory = SeatInventory.reconstitute("showtime-1", List.of(
                new Seat(new SeatCode("B1"), SeatTier.VIP, PRICE),
                new Seat(new SeatCode("A1"), SeatTier.VIP, PRICE)
        ));
        when(seatInventoryRepository.findByShowtimeId("showtime-1")).thenReturn(Optional.of(inventory));

        GetSeatingLayoutUseCase.LayoutResult result = service.getLayout("showtime-1").orElseThrow();

        assertThat(result.sections().get(0).blocks())
                .extracting(GetSeatingLayoutUseCase.BlockSummary::row)
                .containsExactly("A", "B");
    }
}
