package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.out.SeatAvailabilityProjectionPort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort.IncrementResult;
import com.aireak.catalog.application.port.out.SeatCounterUpdatePort.IncrementStatus;
import com.aireak.catalog.application.port.out.ShowtimeSeatCountPort;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The dual write of a seat return: Postgres first and idempotently, Redis second and atomically,
 * and a reseed from Postgres whenever the two could have ended up apart.
 */
@ExtendWith(MockitoExtension.class)
class ReturnedSeatsProjectionServiceTest {

    private static final String EVENT_ID = "evt-1";
    private static final String SHOWTIME_ID = "showtime-1";

    @Mock private SeatCounterUpdatePort counterPort;
    @Mock private SeatAvailabilityProjectionPort projectionPort;
    @Mock private ShowtimeSeatCountPort showtimeSeatCountPort;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ReturnedSeatsProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ReturnedSeatsProjectionService(counterPort, projectionPort, showtimeSeatCountPort, meterRegistry);
    }

    private double count(String metric, String tag, String value) {
        return meterRegistry.counter(metric, tag, value).count();
    }

    @Test
    void writesPostgresFirstAndRedisSecond() {
        when(projectionPort.incrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 2)).thenReturn(true);
        when(counterPort.increment(SHOWTIME_ID, 2)).thenReturn(new IncrementResult(IncrementStatus.APPLIED, 52));

        assertThat(service.applyReturnedSeats(EVENT_ID, SHOWTIME_ID, 2)).isTrue();

        InOrder durableFirst = inOrder(projectionPort, counterPort);
        durableFirst.verify(projectionPort).incrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 2);
        durableFirst.verify(counterPort).increment(SHOWTIME_ID, 2);
        verify(counterPort, never()).reseed(anyString(), anyInt());
        assertThat(count("catalog.seat_return_projection", "outcome", "applied")).isEqualTo(1.0);
    }

    /** Nothing reached Redis, so nothing there is undone — the retry redoes the whole event. */
    @Test
    void aPostgresFailureLeavesRedisAloneAndReachesTheErrorHandler() {
        when(projectionPort.incrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 1))
                .thenThrow(new IllegalStateException("postgres down"));

        assertThatThrownBy(() -> service.applyReturnedSeats(EVENT_ID, SHOWTIME_ID, 1))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(counterPort);
        assertThat(count("catalog.seat_return_projection", "outcome", "failed")).isEqualTo(1.0);
    }

    /**
     * Postgres already has this event. Whether the first attempt reached Redis is unknowable, so the
     * counter is re-derived rather than incremented again — which could count the seats twice.
     */
    @Test
    void aRepeatedEventReseedsRedisFromPostgresAndNeverAddsTwice() {
        when(projectionPort.incrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 1)).thenReturn(false);
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.of(51));

        assertThat(service.applyReturnedSeats(EVENT_ID, SHOWTIME_ID, 1)).isFalse();

        verify(counterPort, never()).increment(anyString(), anyInt());
        verify(counterPort).reseed(SHOWTIME_ID, 51);
        assertThat(count("catalog.seat_return_projection", "outcome", "duplicate")).isEqualTo(1.0);
    }

    @Test
    void aMissingRedisCounterIsReseededFromThePostgresRowThatAlreadyCountsTheReturn() {
        when(projectionPort.incrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 3)).thenReturn(true);
        when(counterPort.increment(SHOWTIME_ID, 3)).thenReturn(IncrementResult.nothingWritten(IncrementStatus.NOT_INITIALIZED));
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.of(80));

        service.applyReturnedSeats(EVENT_ID, SHOWTIME_ID, 3);

        verify(counterPort).reseed(SHOWTIME_ID, 80);
        assertThat(count("catalog.seat_counter.drift", "reason", "not_initialized")).isEqualTo(1.0);
    }

    @Test
    void anUnreachableRedisIsStillOfferedTheReseed() {
        when(projectionPort.incrementAvailableSeats(EVENT_ID, SHOWTIME_ID, 1)).thenReturn(true);
        when(counterPort.increment(SHOWTIME_ID, 1)).thenReturn(IncrementResult.nothingWritten(IncrementStatus.UNAVAILABLE));
        when(showtimeSeatCountPort.findAvailableSeats(SHOWTIME_ID)).thenReturn(Optional.of(10));

        assertThat(service.applyReturnedSeats(EVENT_ID, SHOWTIME_ID, 1)).isTrue();

        verify(counterPort).reseed(SHOWTIME_ID, 10);
        assertThat(count("catalog.seat_counter.drift", "reason", "unavailable")).isEqualTo(1.0);
    }

    @Test
    void registersItsOutcomesAtZeroSoAHealthyCatalogAnswersZeroNotNoData() {
        assertThat(meterRegistry.find("catalog.seat_return_projection").counters()).hasSize(3);
    }
}
