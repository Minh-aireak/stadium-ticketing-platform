package com.aireak.booking.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatSelectionTest {

    @Test
    void countReturnsTheNumberOfSeats() {
        SeatSelection selection = new SeatSelection(List.of("A1", "A2", "A3"));

        assertThat(selection.count()).isEqualTo(3);
    }

    @Test
    void rejectsAnEmptyList() {
        assertThatThrownBy(() -> new SeatSelection(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new SeatSelection(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void seatCodesIsImmutable() {
        SeatSelection selection = new SeatSelection(List.of("A1", "A2"));

        assertThatThrownBy(() -> selection.seatCodes().add("A3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutatingTheSourceListAfterConstructionDoesNotAffectTheSelection() {
        List<String> source = new java.util.ArrayList<>(List.of("A1", "A2"));
        SeatSelection selection = new SeatSelection(source);

        source.add("A3");

        assertThat(selection.count()).isEqualTo(2);
    }

    // Intentionally permissive: this constructor also runs when reconstituting a SeatSelection
    // from an already-persisted booking row (BookingPersistenceAdapter#toDomain). Rejecting
    // duplicate seat codes is a business rule that only applies to NEW input, so it lives in
    // Booking.create() instead (see BookingTest#createRejectsDuplicateSeatCodes) — the same
    // split already used for the MAX_TICKETS rule.
    @Test
    void allowsDuplicateSeatCodesSoThatAnAlreadyPersistedRowCanStillBeReconstituted() {
        SeatSelection selection = new SeatSelection(List.of("A1", "A1", "A1"));

        assertThat(selection.count()).isEqualTo(3);
    }
}
