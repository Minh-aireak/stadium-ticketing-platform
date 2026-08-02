package com.aireak.inventory.application.port.in;

import java.util.List;
import java.util.Optional;

/**
 * Inbound port: read the static seating topology (sections/blocks) for a showtime, for the
 * frontend's Section/Block picker. Unlike {@link GetSeatMapUseCase}, this never reflects live
 * hold/sold state — just which seats belong to which section/block.
 */
public interface GetSeatingLayoutUseCase {

    Optional<LayoutResult> getLayout(String showtimeId);

    record LayoutResult(String showtimeId, List<SectionSummary> sections) {}

    /** {@code tier} is one of {@code VIP}/{@code PREMIUM}/{@code STANDARD} — doubles as the section. */
    record SectionSummary(String tier, List<BlockSummary> blocks) {}

    /** {@code row} is the single-letter row (e.g. {@code "A"}) — doubles as the block. */
    record BlockSummary(String row, List<String> seatCodes) {}
}
