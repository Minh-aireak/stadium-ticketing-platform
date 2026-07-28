package com.aireak.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the default seat map for a newly-created showtime: fixed rows of
 * {@value #SEATS_PER_ROW} seats each, tier assigned by row (rows A/B = VIP, C/D = Premium,
 * everything after = Standard — mirrors the platform's previous frontend mock so the on-screen
 * layout/pricing users already saw doesn't change), price snapshotted as
 * {@code basePrice × tier multiplier} at generation time (see {@link Seat}).
 *
 * <p>Deliberately simple for now — no real per-venue seating chart (arbitrary row shapes, gaps,
 * accessibility seats, etc.), which would be a much larger feature. Row letters run A-Z only
 * ({@link #MAX_SEATS} cap), matching {@link SeatCode}'s single-letter-row format.
 */
public final class SeatMapLayout {

    private static final int SEATS_PER_ROW = 10;
    private static final int MAX_ROWS = 26; // 'A'..'Z' — SeatCode only supports a single row letter
    private static final int MAX_SEATS = MAX_ROWS * SEATS_PER_ROW;

    private static final int VIP_ROWS = 2;      // rows A, B
    private static final int PREMIUM_ROWS = 2;  // rows C, D

    private static final BigDecimal VIP_MULTIPLIER = new BigDecimal("2.2");
    private static final BigDecimal PREMIUM_MULTIPLIER = new BigDecimal("1.5");
    private static final BigDecimal STANDARD_MULTIPLIER = BigDecimal.ONE;

    private SeatMapLayout() {
        // static factory only
    }

    public static List<Seat> generate(int totalSeats, BigDecimal basePrice) {
        if (totalSeats <= 0) {
            throw new IllegalArgumentException("totalSeats must be positive: " + totalSeats);
        }
        if (totalSeats > MAX_SEATS) {
            throw new IllegalArgumentException(
                    "totalSeats exceeds the default layout's capacity (" + MAX_SEATS + "): " + totalSeats);
        }

        List<Seat> seats = new ArrayList<>(totalSeats);
        int remaining = totalSeats;
        for (int rowIndex = 0; remaining > 0; rowIndex++) {
            char rowLetter = (char) ('A' + rowIndex);
            SeatTier tier = tierForRow(rowIndex);
            BigDecimal price = priceFor(basePrice, tier);
            int seatsInRow = Math.min(SEATS_PER_ROW, remaining);

            for (int number = 1; number <= seatsInRow; number++) {
                seats.add(new Seat(new SeatCode(rowLetter + String.valueOf(number)), tier, price));
            }
            remaining -= seatsInRow;
        }
        return seats;
    }

    private static SeatTier tierForRow(int rowIndex) {
        if (rowIndex < VIP_ROWS) return SeatTier.VIP;
        if (rowIndex < VIP_ROWS + PREMIUM_ROWS) return SeatTier.PREMIUM;
        return SeatTier.STANDARD;
    }

    private static BigDecimal priceFor(BigDecimal basePrice, SeatTier tier) {
        BigDecimal multiplier = switch (tier) {
            case VIP -> VIP_MULTIPLIER;
            case PREMIUM -> PREMIUM_MULTIPLIER;
            case STANDARD -> STANDARD_MULTIPLIER;
        };
        return basePrice.multiply(multiplier).setScale(0, RoundingMode.HALF_UP);
    }
}
