package com.aireak.inventory.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatMapLayoutTest {

    private static final BigDecimal BASE_PRICE = new BigDecimal("100000");

    @Test
    void generatesExactlyTotalSeatsAcrossRowsOfTen() {
        List<Seat> seats = SeatMapLayout.generate(25, BASE_PRICE);

        assertThat(seats).hasSize(25);
        // 3 rows: A (10), B (10), C (5)
        assertThat(seats).extracting(s -> s.getSeatCode().value())
                .contains("A1", "A10", "B1", "B10", "C1", "C5")
                .doesNotContain("C6");
    }

    @Test
    void assignsVipToFirstTwoRowsPremiumToNextTwoStandardAfter() {
        List<Seat> seats = SeatMapLayout.generate(50, BASE_PRICE); // 5 full rows: A-E

        Map<String, SeatTier> tierByRow = Map.of(
                "A", SeatTier.VIP, "B", SeatTier.VIP,
                "C", SeatTier.PREMIUM, "D", SeatTier.PREMIUM,
                "E", SeatTier.STANDARD);

        seats.forEach(seat -> {
            String row = seat.getSeatCode().value().substring(0, 1);
            assertThat(seat.getTier()).as("row %s", row).isEqualTo(tierByRow.get(row));
        });
    }

    @Test
    void snapshotsPriceAsBasePriceTimesTierMultiplier() {
        List<Seat> seats = SeatMapLayout.generate(40, BASE_PRICE); // rows A-D

        Map<String, BigDecimal> expectedPriceByRow = Map.of(
                "A", new BigDecimal("220000"), // VIP x2.2
                "B", new BigDecimal("220000"),
                "C", new BigDecimal("150000"), // PREMIUM x1.5
                "D", new BigDecimal("150000"));

        seats.forEach(seat -> {
            String row = seat.getSeatCode().value().substring(0, 1);
            assertThat(seat.getPrice()).as("row %s", row).isEqualByComparingTo(expectedPriceByRow.get(row));
        });
    }

    @Test
    void everySeatStartsAvailable() {
        List<Seat> seats = SeatMapLayout.generate(10, BASE_PRICE);

        assertThat(seats).allSatisfy(seat -> assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE));
    }

    @Test
    void rejectsNonPositiveTotalSeats() {
        assertThatThrownBy(() -> SeatMapLayout.generate(0, BASE_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatMapLayout.generate(-1, BASE_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTotalSeatsBeyondLayoutCapacity() {
        assertThatThrownBy(() -> SeatMapLayout.generate(261, BASE_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactlyTheLayoutCapacity() {
        List<Seat> seats = SeatMapLayout.generate(260, BASE_PRICE);

        assertThat(seats).hasSize(260);
        assertThat(seats).extracting(s -> s.getSeatCode().value()).contains("Z10");
    }
}
