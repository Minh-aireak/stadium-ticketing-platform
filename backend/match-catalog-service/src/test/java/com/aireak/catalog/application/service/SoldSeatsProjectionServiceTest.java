package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.in.CheckSeatCapacityUseCase;
import com.aireak.catalog.application.port.out.SeatAvailabilityProjectionPort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort.DecrementResult;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort.DecrementStatus;
import com.aireak.catalog.application.port.out.ShowtimeSeatCountPort;
import com.aireak.catalog.domain.model.SeatCapacityCheck;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the sequencing this service exists for: Redis first and atomically, Postgres second and
 * durably, and then whichever of undo/reconcile the pair of outcomes calls for. Every way the two
 * legs can end up disagreeing has a case here.
 */
@ExtendWith(MockitoExtension.class)
class SoldSeatsProjectionServiceTest {

    private static final String EVENT_ID = "evt-1";
    private static final String SHOWTIME_ID = "showtime-1";

    @Mock
    private CheckSeatCapacityUseCase seatCapacityCheck;
    @Mock
    private SeatCounterUpdatePort counterPort;
    @Mock
    private SeatAvailabilityProjectionPort projectionPort;
    @Mock
    private ShowtimeSeatCountPort showtimeSeatCountPort;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private SoldSeatsProjectionService service;

    @BeforeEach
    void setUp() {
        // Every path through the service asks this first; lenient so the handful of tests that care
        // about the answer can re-stub it without the default counting as an unused stubbing.
        lenient().when(seatCapacityCheck.checkCapacity(anyString(), anyInt()))
                .thenReturn(SeatCapacityCheck.of(SHOWTIME_ID, 3, 100));
        service = new SoldSeatsProjectionService(
                seatCapacityCheck, counterPort, projectionPort, showtimeSeatCountPort, meterRegistry);
    }

    @Test
    void appliesToRedisFirstAndPostgresSecond() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.APPLIED, 97, 3));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true);

        assertThat(service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3)).isTrue();

        InOrder order = inOrder(seatCapacityCheck, counterPort, projectionPort);
        order.verify(seatCapacityCheck).checkCapacity(SHOWTIME_ID, 3);
        order.verify(counterPort).decrement(SHOWTIME_ID, 3);
        order.verify(projectionPort).decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3);
    }

    @Test
    void aCleanRunTouchesNeitherTheUndoNorTheReconcilePath() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.APPLIED, 97, 3));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true);

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3);

        verify(counterPort, never()).restore(anyString(), anyInt());
        verify(counterPort, never()).reseed(anyString(), anyInt());
        verify(showtimeSeatCountPort, never()).findAvailableSeats(anyString());
    }

    /**
     * Redis moved but Postgres did not, so the seats Redis gave away have to come back — otherwise
     * the counter permanently understates availability for a sale that never happened.
     */
    @Test
    void aFailedPostgresWriteHandsTheSeatsBackToRedisAndRethrows() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.APPLIED, 97, 3));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3))
                .thenThrow(new IllegalStateException("showtime vanished"));

        assertThatThrownBy(() -> service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3))
                .isInstanceOf(IllegalStateException.class);

        verify(counterPort).restore(SHOWTIME_ID, 3);
    }

    /**
     * Postgres carries the idempotency record; the Redis counter has none, so a redelivered event
     * decrements it a second time and that second decrement has to be undone.
     */
    @Test
    void aRedeliveredEventUndoesTheRedisDecrementItRepeated() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.APPLIED, 97, 3));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(false);

        assertThat(service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3)).isFalse();

        verify(counterPort).restore(SHOWTIME_ID, 3);
        verify(counterPort, never()).reseed(anyString(), anyInt());
    }

    /** A clamped decrement took less than it was asked for, so only that much may be handed back. */
    @Test
    void anUndoRestoresWhatWasActuallyDeductedNotWhatWasRequested() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.CLAMPED, 0, 2));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 5)).thenReturn(false);

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 5);

        verify(counterPort).restore(SHOWTIME_ID, 2);
    }

    @Test
    void aLegThatNeverWroteHasNothingToUndo() {
        givenRedisDecrement(DecrementResult.nothingWritten(DecrementStatus.UNAVAILABLE));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(false);

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3);

        verify(counterPort, never()).restore(anyString(), anyInt());
    }

    /** The recovery path for an expired or never-seeded counter: rebuild it from the source of truth. */
    @Test
    void anAbsentCounterIsRebuiltFromPostgresAfterTheProjectionCommits() {
        givenRedisDecrement(DecrementResult.nothingWritten(DecrementStatus.NOT_INITIALIZED));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true);
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.of(97));

        assertThat(service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3)).isTrue();

        verify(counterPort).reseed(SHOWTIME_ID, 97);
        verify(counterPort, never()).restore(anyString(), anyInt());
    }

    /** Hitting the floor means Redis had fallen behind Postgres — the drift is repaired, not ignored. */
    @Test
    void aClampedDecrementIsReconciledAgainstPostgres() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.CLAMPED, 0, 2));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 5)).thenReturn(true);
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.of(40));

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 5);

        verify(counterPort).reseed(SHOWTIME_ID, 40);
    }

    @Test
    void anUnreachableRedisStillLetsThePostgresProjectionStand() {
        givenRedisDecrement(DecrementResult.nothingWritten(DecrementStatus.UNAVAILABLE));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true);
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.of(97));

        assertThat(service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3)).isTrue();

        verify(counterPort).reseed(SHOWTIME_ID, 97);
    }

    @Test
    void aShowtimeWithNoRowLeavesTheCounterAloneRatherThanGuessing() {
        givenRedisDecrement(DecrementResult.nothingWritten(DecrementStatus.NOT_INITIALIZED));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true);
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.empty());

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3);

        verify(counterPort, never()).reseed(anyString(), anyInt());
    }

    // ----------------------------------------------------------------
    // Metrics — the branches above matter operationally, and a log line alone cannot be alerted on
    // from Prometheus. Each assertion here pins the meter an alert rule would be written against.
    // ----------------------------------------------------------------

    @Test
    void countsEveryProjectionUnderTheOutcomeItReached() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.APPLIED, 97, 3));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true, false);

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3);
        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3);

        assertThat(count("catalog.seat_projection", "outcome", "applied")).isEqualTo(1);
        assertThat(count("catalog.seat_projection", "outcome", "duplicate")).isEqualTo(1);
        assertThat(count("catalog.seat_projection", "outcome", "failed")).isZero();
    }

    @Test
    void countsAFailedProjectionSeparatelyFromADuplicateOne() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.APPLIED, 97, 3));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3))
                .thenThrow(new IllegalStateException("postgres down"));

        assertThatThrownBy(() -> service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("catalog.seat_projection", "outcome", "failed")).isEqualTo(1);
    }

    /** Movement here means inventory sold more seats than the catalog believed were left. */
    @Test
    void countsASaleThatArrivesForMoreSeatsThanTheCounterHolds() {
        when(seatCapacityCheck.checkCapacity(SHOWTIME_ID, 5))
                .thenReturn(SeatCapacityCheck.of(SHOWTIME_ID, 5, 2));
        givenRedisDecrement(new DecrementResult(DecrementStatus.CLAMPED, 0, 2));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 5)).thenReturn(true);
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.of(40));

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 5);

        assertThat(count("catalog.seat_counter.oversell")).isEqualTo(1);
    }

    @Test
    void countsDriftUnderTheReasonTheCounterHadToBeRebuilt() {
        givenRedisDecrement(DecrementResult.nothingWritten(DecrementStatus.NOT_INITIALIZED));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true);
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.of(97));

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3);

        assertThat(count("catalog.seat_counter.drift", "reason", "not_initialized")).isEqualTo(1);
        assertThat(count("catalog.seat_counter.drift", "reason", "clamped")).isZero();
    }

    @Test
    void countsNoDriftWhenRedisAndPostgresAgreed() {
        givenRedisDecrement(new DecrementResult(DecrementStatus.APPLIED, 97, 3));
        when(projectionPort.decrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true);

        service.applySoldSeats(EVENT_ID, SHOWTIME_ID, 3);

        assertThat(meterRegistry.find("catalog.seat_counter.drift").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(count("catalog.seat_counter.oversell")).isZero();
    }

    /**
     * A counter Micrometer has never seen is a series Prometheus does not have, and "no data" on
     * the oversell panel is indistinguishable from a catalog that is not being scraped. Every
     * meter, with every tag value the service can emit, has to exist at zero before the first sale.
     */
    @Test
    void registersEveryMeterAtZeroBeforeTheFirstSale() {
        assertThat(meterRegistry.find("catalog.seat_counter.oversell").counter()).isNotNull();
        assertThat(count("catalog.seat_counter.oversell")).isZero();

        assertThat(meterRegistry.find("catalog.seat_projection").counters())
                .extracting(counter -> counter.getId().getTag("outcome"))
                .containsExactlyInAnyOrder("applied", "duplicate", "failed");

        assertThat(meterRegistry.find("catalog.seat_counter.drift").counters())
                .extracting(counter -> counter.getId().getTag("reason"))
                .containsExactlyInAnyOrder("clamped", "not_initialized", "corrupt", "unavailable");
        assertThat(meterRegistry.find("catalog.seat_counter.drift").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    private void givenRedisDecrement(DecrementResult result) {
        when(counterPort.decrement(anyString(), anyInt())).thenReturn(result);
    }

    private double count(String name, String... tags) {
        return meterRegistry.counter(name, tags).count();
    }
}
