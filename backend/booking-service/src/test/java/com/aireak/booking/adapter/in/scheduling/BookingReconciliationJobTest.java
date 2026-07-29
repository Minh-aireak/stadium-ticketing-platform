package com.aireak.booking.adapter.in.scheduling;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.SeatSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingReconciliationJobTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentPort paymentPort;
    @Mock
    private BookingOrchestrationService bookingOrchestrationService;

    private BookingReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new BookingReconciliationJob(bookingRepository, paymentPort, bookingOrchestrationService);
    }

    private Booking pendingPaymentBooking(String bookingId) {
        return Booking.reconstitute(bookingId, "customer-1", "showtime-1",
                new SeatSelection(List.of("A1")), BookingAmount.of(new BigDecimal("100.00"), "USD"),
                BookingStatus.PENDING_PAYMENT, Instant.now(), null, 0L, false);
    }

    @Test
    void doesNothingWhenNoBookingsAreStuck() {
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt())).thenReturn(List.of());

        job.reconcile();

        verify(paymentPort, never()).checkOutcome(any());
    }

    @Test
    void confirmsBookingWhenPaymentServiceReportsSucceeded() {
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1")));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(Optional.of(PaymentPort.PaymentOutcome.SUCCEEDED));

        job.reconcile();

        verify(bookingOrchestrationService).confirmBooking("booking-1");
        verify(bookingOrchestrationService, never()).cancelBookingOnPaymentFailure(any(), any());
    }

    @Test
    void cancelsBookingWhenPaymentServiceReportsFailed() {
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1")));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(Optional.of(PaymentPort.PaymentOutcome.FAILED));

        job.reconcile();

        verify(bookingOrchestrationService).cancelBookingOnPaymentFailure(eq("booking-1"), any());
        verify(bookingOrchestrationService, never()).confirmBooking(any());
    }

    @Test
    void leavesBookingAloneWhenOutcomeIsStillUnresolved() {
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1")));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(Optional.empty());

        job.reconcile();

        verify(bookingOrchestrationService, never()).confirmBooking(any());
        verify(bookingOrchestrationService, never()).cancelBookingOnPaymentFailure(any(), any());
    }

    @Test
    void aFailureReconcilingOneBookingDoesNotStopTheRestOfTheBatch() {
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1"), pendingPaymentBooking("booking-2")));
        when(paymentPort.checkOutcome("booking-1")).thenThrow(new RuntimeException("payment-service down"));
        when(paymentPort.checkOutcome("booking-2")).thenReturn(Optional.of(PaymentPort.PaymentOutcome.SUCCEEDED));

        job.reconcile();

        verify(bookingOrchestrationService, never()).confirmBooking("booking-1");
        verify(bookingOrchestrationService).confirmBooking("booking-2");
    }

    @Test
    void queriesUsingTheConfiguredGracePeriodAndBatchSize() throws Exception {
        setField(job, "graceMinutes", 10L);
        setField(job, "batchSize", 50);
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt())).thenReturn(List.of());

        job.reconcile();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(bookingRepository, times(1)).findPendingPaymentOlderThan(cutoffCaptor.capture(), eq(50));
        Instant expectedCutoff = Instant.now().minus(10, ChronoUnit.MINUTES);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = BookingReconciliationJob.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
