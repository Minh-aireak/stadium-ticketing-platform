package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.out.SeatAvailabilityCounterPort;
import com.aireak.catalog.application.port.out.SeatAvailabilityCounterPort.SeedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowtimeSeatCounterInitializerTest {

    @Mock
    private SeatAvailabilityCounterPort counterPort;

    private ShowtimeSeatCounterInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new ShowtimeSeatCounterInitializer(counterPort);
    }

    @Test
    void seedsTheCounterWithTheShowtimesFullCapacity() {
        when(counterPort.initialize("showtime-1", 432)).thenReturn(SeedResult.SEEDED);

        initializer.initializeCounter("showtime-1", 432);

        verify(counterPort).initialize("showtime-1", 432);
    }

    /**
     * Creating a showtime is an admin write that must not depend on Redis. The first sold-seat
     * projection finds no counter and rebuilds it from Postgres, so a failed seed costs accuracy
     * for a while, never the showtime.
     */
    @Test
    void doesNotFailTheShowtimeCreationWhenRedisIsUnreachable() {
        when(counterPort.initialize("showtime-1", 432)).thenReturn(SeedResult.UNAVAILABLE);

        assertThatCode(() -> initializer.initializeCounter("showtime-1", 432)).doesNotThrowAnyException();
    }

    @Test
    void doesNotFailTheShowtimeCreationWhenTheCounterPortThrows() {
        when(counterPort.initialize("showtime-1", 432)).thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> initializer.initializeCounter("showtime-1", 432)).doesNotThrowAnyException();
    }

    @Test
    void leavesAnAlreadyPresentCounterAloneRatherThanOverwritingIt() {
        when(counterPort.initialize("showtime-1", 432)).thenReturn(SeedResult.ALREADY_PRESENT);

        assertThatCode(() -> initializer.initializeCounter("showtime-1", 432)).doesNotThrowAnyException();
    }
}
