package com.aireak.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Generates immutable seat maps from one of the platform's fixed stadium layouts. */
public final class SeatMapLayout {

    public static final String MY_DINH = "my-dinh";
    public static final String THONG_NHAT = "thong-nhat";
    public static final String HANG_DAY = "hang-day";

    private static final BigDecimal VIP_MULTIPLIER = new BigDecimal("2.2");
    private static final BigDecimal PREMIUM_MULTIPLIER = new BigDecimal("1.5");

    private static final Map<String, StadiumDefinition> STADIUMS = Map.of(
            MY_DINH, new StadiumDefinition(MY_DINH, List.of(
                    row('A', 56, SeatTier.VIP), row('B', 60, SeatTier.VIP),
                    row('C', 64, SeatTier.PREMIUM), row('D', 80, SeatTier.PREMIUM),
                    row('E', 84, SeatTier.STANDARD), row('F', 88, SeatTier.STANDARD))),
            THONG_NHAT, new StadiumDefinition(THONG_NHAT, List.of(
                    row('A', 56, SeatTier.VIP), row('B', 60, SeatTier.PREMIUM),
                    row('C', 64, SeatTier.PREMIUM), row('D', 68, SeatTier.STANDARD),
                    row('E', 72, SeatTier.STANDARD))),
            HANG_DAY, new StadiumDefinition(HANG_DAY, List.of(
                    row('A', 40, SeatTier.VIP), row('B', 44, SeatTier.VIP),
                    row('C', 48, SeatTier.PREMIUM), row('D', 56, SeatTier.PREMIUM),
                    row('E', 60, SeatTier.PREMIUM), row('F', 64, SeatTier.STANDARD),
                    row('G', 72, SeatTier.STANDARD), row('H', 76, SeatTier.STANDARD),
                    row('I', 80, SeatTier.STANDARD)))
    );

    private SeatMapLayout() {
    }

    public static List<Seat> generate(String stadiumId, BigDecimal basePrice) {
        StadiumDefinition stadium = STADIUMS.get(stadiumId);
        if (stadium == null) {
            throw new IllegalArgumentException("Unknown stadium layout: " + stadiumId);
        }
        if (basePrice == null || basePrice.signum() <= 0) {
            throw new IllegalArgumentException("basePrice must be positive");
        }

        List<Seat> seats = new ArrayList<>(stadium.totalSeats());
        for (RowDefinition row : stadium.rows()) {
            BigDecimal price = priceFor(basePrice, row.tier());
            for (int number = 1; number <= row.seats(); number++) {
                seats.add(new Seat(new SeatCode(row.label() + String.valueOf(number)), row.tier(), price));
            }
        }
        return seats;
    }

    public static int capacity(String stadiumId) {
        StadiumDefinition stadium = STADIUMS.get(stadiumId);
        if (stadium == null) throw new IllegalArgumentException("Unknown stadium layout: " + stadiumId);
        return stadium.totalSeats();
    }

    private static RowDefinition row(char label, int seats, SeatTier tier) {
        return new RowDefinition(label, seats, tier);
    }

    private static BigDecimal priceFor(BigDecimal basePrice, SeatTier tier) {
        BigDecimal multiplier = switch (tier) {
            case VIP -> VIP_MULTIPLIER;
            case PREMIUM -> PREMIUM_MULTIPLIER;
            case STANDARD -> BigDecimal.ONE;
        };
        return basePrice.multiply(multiplier).setScale(0, RoundingMode.HALF_UP);
    }

    private record RowDefinition(char label, int seats, SeatTier tier) {
    }

    private record StadiumDefinition(String id, List<RowDefinition> rows) {
        int totalSeats() {
            return rows.stream().mapToInt(RowDefinition::seats).sum();
        }
    }
}
