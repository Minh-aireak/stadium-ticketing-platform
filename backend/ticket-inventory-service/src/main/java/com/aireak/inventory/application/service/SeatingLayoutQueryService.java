package com.aireak.inventory.application.service;

import com.aireak.inventory.application.port.in.GetSeatingLayoutUseCase;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatTier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Application service: reads the seating topology (sections/blocks) for a showtime.
 *
 * <p>There is no persisted venue layout — see {@code SeatMapLayout}'s javadoc: rows/tiers are a
 * fixed generation convention, not real per-venue seating-chart data — so sections/blocks are
 * derived from the already-persisted seats: tier -> section, row letter -> block. Static topology
 * only; unlike {@link SeatMapQueryService}, this never touches {@code SeatHoldPort}/Redis.
 */
@Service
@RequiredArgsConstructor
public class SeatingLayoutQueryService implements GetSeatingLayoutUseCase {

    // Display order only (VIP first) — grouping itself is driven by each seat's actual tier.
    private static final List<SeatTier> SECTION_ORDER = List.of(SeatTier.VIP, SeatTier.PREMIUM, SeatTier.STANDARD);

    private final SeatInventoryRepository seatInventoryRepository;

    @Override
    public Optional<LayoutResult> getLayout(String showtimeId) {
        return seatInventoryRepository.findByShowtimeId(showtimeId)
                .map(inventory -> toResult(showtimeId, inventory));
    }

    private LayoutResult toResult(String showtimeId, SeatInventory inventory) {
        Map<SeatTier, Map<String, List<String>>> byTierThenRow = new LinkedHashMap<>();
        for (Seat seat : inventory.getSeats()) {
            String code = seat.getSeatCode().value();
            String row = code.substring(0, 1);
            byTierThenRow
                    .computeIfAbsent(seat.getTier(), t -> new TreeMap<>())
                    .computeIfAbsent(row, r -> new ArrayList<>())
                    .add(code);
        }

        List<SectionSummary> sections = SECTION_ORDER.stream()
                .filter(byTierThenRow::containsKey)
                .map(tier -> toSection(tier, byTierThenRow.get(tier)))
                .toList();
        return new LayoutResult(showtimeId, sections);
    }

    private SectionSummary toSection(SeatTier tier, Map<String, List<String>> seatCodesByRow) {
        List<BlockSummary> blocks = seatCodesByRow.entrySet().stream()
                .map(entry -> new BlockSummary(entry.getKey(), sortByNumber(entry.getValue())))
                .toList();
        return new SectionSummary(tier.name(), blocks);
    }

    private List<String> sortByNumber(List<String> seatCodes) {
        return seatCodes.stream()
                .sorted(Comparator.comparingInt(code -> Integer.parseInt(code.substring(1))))
                .toList();
    }
}
