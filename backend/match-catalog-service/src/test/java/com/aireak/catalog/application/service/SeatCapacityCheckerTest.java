package com.aireak.catalog.application.service;

import com.aireak.catalog.application.port.out.SeatAvailabilityCounterPort;
import com.aireak.catalog.domain.model.SeatCapacityCheck;
import com.aireak.catalog.domain.model.SeatCapacityCheck.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatCapacityCheckerTest {

    @Mock
    private SeatAvailabilityCounterPort counterPort;

    private SeatCapacityChecker checker;

    @BeforeEach
    void setUp() {
        checker = new SeatCapacityChecker(counterPort);
    }

    @Test
    void passesTheCountersVerdictStraightBackToTheCaller() {
        when(counterPort.check("showtime-1", 4))
                .thenReturn(SeatCapacityCheck.of("showtime-1", 4, 10));

        SeatCapacityCheck check = checker.checkCapacity("showtime-1", 4);

        assertThat(check.verdict()).isEqualTo(Verdict.WITHIN_CAPACITY);
        assertThat(check.remainingAfter()).isEqualTo(6);
    }

    @Test
    void reportsTheShortfallWhenTheRequestIsLargerThanWhatIsLeft() {
        when(counterPort.check("showtime-1", 9))
                .thenReturn(SeatCapacityCheck.of("showtime-1", 9, 4));

        SeatCapacityCheck check = checker.checkCapacity("showtime-1", 9);

        assertThat(check.exceedsCapacity()).isTrue();
        assertThat(check.shortfall()).isEqualTo(5);
    }

    @Test
    void anUnknownAvailabilityIsNotReportedAsRoom() {
        when(counterPort.check("showtime-1", 9))
                .thenReturn(SeatCapacityCheck.unknown("showtime-1", 9));

        SeatCapacityCheck check = checker.checkCapacity("showtime-1", 9);

        assertThat(check.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(check.exceedsCapacity()).isFalse();
        assertThat(check.isKnown()).isFalse();
    }

    /** Reading must stay a read — nothing here may hold, reserve or decrement anything. */
    @Test
    void neverMutatesTheCounter() {
        when(counterPort.check("showtime-1", 4))
                .thenReturn(SeatCapacityCheck.of("showtime-1", 4, 10));

        checker.checkCapacity("showtime-1", 4);

        verify(counterPort).check("showtime-1", 4);
        org.mockito.Mockito.verifyNoMoreInteractions(counterPort);
    }
}
