package com.aireak.inventory.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatMapLayoutTest {

    private static final BigDecimal BASE_PRICE = new BigDecimal("100000");

    @Test
    void generatesTheFixedCapacityForEveryStadium() {
        assertThat(SeatMapLayout.generate(SeatMapLayout.MY_DINH, BASE_PRICE)).hasSize(432);
        assertThat(SeatMapLayout.generate(SeatMapLayout.THONG_NHAT, BASE_PRICE)).hasSize(320);
        assertThat(SeatMapLayout.generate(SeatMapLayout.HANG_DAY, BASE_PRICE)).hasSize(540);
    }

    @Test
    void myDinhUsesTheApprovedSixRowShape() {
        List<Seat> seats = SeatMapLayout.generate(SeatMapLayout.MY_DINH, BASE_PRICE);

        assertThat(seats).extracting(seat -> seat.getSeatCode().value())
                .contains("A1", "A56", "B60", "C64", "D80", "E84", "F88")
                .doesNotContain("A57", "F89");
    }

    @Test
    void hangDayUsesNineRowsForThreeLevels() {
        List<Seat> seats = SeatMapLayout.generate(SeatMapLayout.HANG_DAY, BASE_PRICE);

        assertThat(seats).extracting(seat -> seat.getSeatCode().value())
                .contains("A40", "C48", "F64", "I80")
                .doesNotContain("I81", "J1");
    }

    @Test
    void snapshotsPriceFromEachRowsTier() {
        List<Seat> seats = SeatMapLayout.generate(SeatMapLayout.MY_DINH, BASE_PRICE);

        Seat vip = seats.stream().filter(seat -> seat.getSeatCode().value().equals("A1")).findFirst().orElseThrow();
        Seat premium = seats.stream().filter(seat -> seat.getSeatCode().value().equals("C1")).findFirst().orElseThrow();
        Seat standard = seats.stream().filter(seat -> seat.getSeatCode().value().equals("F1")).findFirst().orElseThrow();

        assertThat(vip.getTier()).isEqualTo(SeatTier.VIP);
        assertThat(vip.getPrice()).isEqualByComparingTo("220000");
        assertThat(premium.getTier()).isEqualTo(SeatTier.PREMIUM);
        assertThat(premium.getPrice()).isEqualByComparingTo("150000");
        assertThat(standard.getTier()).isEqualTo(SeatTier.STANDARD);
        assertThat(standard.getPrice()).isEqualByComparingTo("100000");
    }

    @Test
    void everyGeneratedSeatStartsAvailable() {
        assertThat(SeatMapLayout.generate(SeatMapLayout.THONG_NHAT, BASE_PRICE))
                .allSatisfy(seat -> assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE));
    }

    @Test
    void rejectsUnknownStadiumAndInvalidPrice() {
        assertThatThrownBy(() -> SeatMapLayout.generate("unknown", BASE_PRICE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeatMapLayout.generate(SeatMapLayout.MY_DINH, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
