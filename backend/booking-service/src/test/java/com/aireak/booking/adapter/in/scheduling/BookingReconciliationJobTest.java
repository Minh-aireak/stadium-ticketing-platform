package com.aireak.booking.adapter.in.scheduling;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.SeatSelection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    private SimpleMeterRegistry meterRegistry;
    private BookingReconciliationJob job;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        job = new BookingReconciliationJob(bookingRepository, paymentPort, bookingOrchestrationService, meterRegistry);
        // @Value fields are null on a hand-built instance; these are application.yaml's defaults.
        setField(job, "graceMinutes", 5L);
        setField(job, "abandonAfterMinutes", 10L);
        setField(job, "batchSize", 200);
    }

    private Booking draftBooking(String bookingId) {
        return Booking.reconstitute(bookingId, "customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(List.of("A1")), BookingAmount.of(new BigDecimal("100.00"), "USD"),
                BookingStatus.DRAFT, Instant.now(), null, 0L, false, false);
    }

    private Booking pendingPaymentBooking(String bookingId) {
        return pendingPaymentBooking(bookingId, Instant.now());
    }

    private Booking pendingPaymentBooking(String bookingId, Instant createdAt) {
        return Booking.reconstitute(bookingId, "customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(List.of("A1")), BookingAmount.of(new BigDecimal("100.00"), "USD"),
                BookingStatus.PENDING_PAYMENT, createdAt, null, 0L, false, false);
    }

    private double abandonedCount() {
        return meterRegistry.counter("booking.payment.abandoned").count();
    }

    @Test
    void doesNothingWhenNoBookingsAreStuck() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt())).thenReturn(List.of());

        job.reconcile();

        verify(paymentPort, never()).checkOutcome(any());
        verify(bookingOrchestrationService, never()).cancelBookingOnPaymentFailure(any(), any());
    }

    @Test
    void cancelsStaleDraftBookingsAndReleasesSeats() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt()))
                .thenReturn(List.of(draftBooking("draft-1")));
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt())).thenReturn(List.of());

        job.reconcile();

        verify(bookingOrchestrationService).cancelBookingOnPaymentFailure(eq("draft-1"), eq("Draft booking expired"));
    }

    @Test
    void aFailureReconcilingDraftBookingDoesNotStopPendingPaymentReconciliation() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt()))
                .thenReturn(List.of(draftBooking("draft-1")));
        org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
                .when(bookingOrchestrationService).cancelBookingOnPaymentFailure(eq("draft-1"), any());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-2")));
        when(paymentPort.checkOutcome("booking-2")).thenReturn(PaymentPort.PaymentOutcome.SUCCEEDED);

        job.reconcile();

        verify(bookingOrchestrationService).confirmBooking("booking-2");
    }

    @Test
    void confirmsBookingWhenPaymentServiceReportsSucceeded() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1")));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(PaymentPort.PaymentOutcome.SUCCEEDED);

        job.reconcile();

        verify(bookingOrchestrationService).confirmBooking("booking-1");
        verify(bookingOrchestrationService, never()).cancelBookingOnPaymentFailure(any(), any());
    }

    @Test
    void cancelsBookingWhenPaymentServiceReportsFailed() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1")));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(PaymentPort.PaymentOutcome.FAILED);

        job.reconcile();

        verify(bookingOrchestrationService).cancelBookingOnPaymentFailure(eq("booking-1"), any());
        verify(bookingOrchestrationService, never()).confirmBooking(any());
    }

    @Test
    void leavesBookingAloneWhenTheQueryCouldNotBeAnswered() {
        // Old enough to be abandoned had payment-service said NOT_FOUND — but it said nothing, and
        // nothing is never a reason to compensate.
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1", Instant.now().minus(30, ChronoUnit.MINUTES))));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(PaymentPort.PaymentOutcome.UNKNOWN);

        job.reconcile();

        verify(bookingOrchestrationService, never()).confirmBooking(any());
        verify(bookingOrchestrationService, never()).cancelBookingOnPaymentFailure(any(), any());
        assertThat(abandonedCount()).isZero();
    }

    @Test
    void leavesBookingAloneWhilePaymentServiceIsStillWorkingIt() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1", Instant.now().minus(30, ChronoUnit.MINUTES))));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(PaymentPort.PaymentOutcome.IN_FLIGHT);

        job.reconcile();

        verify(bookingOrchestrationService, never()).confirmBooking(any());
        verify(bookingOrchestrationService, never()).cancelBookingOnPaymentFailure(any(), any());
        assertThat(abandonedCount()).isZero();
    }

    /**
     * The case that used to be a permanent leak: payment-service was down for Step 4, the booking
     * committed PENDING_PAYMENT, and once payment-service came back it had — truthfully — no
     * payment to report. Past the abandonment threshold that is the whole story, and the booking
     * goes the way a FAILED payment does.
     */
    @Test
    void abandonsBookingPaymentServiceHasNoPaymentForOnceItIsOldEnough() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1", Instant.now().minus(11, ChronoUnit.MINUTES))));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(PaymentPort.PaymentOutcome.NOT_FOUND);

        job.reconcile();

        verify(bookingOrchestrationService).cancelBookingOnPaymentFailure("booking-1", "No payment was ever initiated");
        verify(bookingOrchestrationService, never()).confirmBooking(any());
        assertThat(abandonedCount()).isEqualTo(1.0);
    }

    @Test
    void givesBookingPaymentServiceHasNoPaymentForTheFullThresholdBeforeAbandoningIt() {
        // Past the grace period (so the query returned it) but short of abandon-after-minutes.
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1", Instant.now().minus(6, ChronoUnit.MINUTES))));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(PaymentPort.PaymentOutcome.NOT_FOUND);

        job.reconcile();

        verify(bookingOrchestrationService, never()).cancelBookingOnPaymentFailure(any(), any());
        verify(bookingOrchestrationService, never()).confirmBooking(any());
        assertThat(abandonedCount()).isZero();
    }

    @Test
    void abandonmentThresholdIsTheConfiguredOne() throws Exception {
        setField(job, "abandonAfterMinutes", 30L);
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1", Instant.now().minus(20, ChronoUnit.MINUTES))));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(PaymentPort.PaymentOutcome.NOT_FOUND);

        job.reconcile();

        verify(bookingOrchestrationService, never()).cancelBookingOnPaymentFailure(any(), any());
        assertThat(abandonedCount()).isZero();
    }

    @Test
    void anAbandonmentThatFailsDoesNotCountAsOne() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1", Instant.now().minus(11, ChronoUnit.MINUTES))));
        when(paymentPort.checkOutcome("booking-1")).thenReturn(PaymentPort.PaymentOutcome.NOT_FOUND);
        org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
                .when(bookingOrchestrationService).cancelBookingOnPaymentFailure(eq("booking-1"), any());

        job.reconcile();

        assertThat(abandonedCount()).isZero();
    }

    @Test
    void aFailureReconcilingOneBookingDoesNotStopTheRestOfTheBatch() {
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt()))
                .thenReturn(List.of(pendingPaymentBooking("booking-1"), pendingPaymentBooking("booking-2")));
        when(paymentPort.checkOutcome("booking-1")).thenThrow(new RuntimeException("payment-service down"));
        when(paymentPort.checkOutcome("booking-2")).thenReturn(PaymentPort.PaymentOutcome.SUCCEEDED);

        job.reconcile();

        verify(bookingOrchestrationService, never()).confirmBooking("booking-1");
        verify(bookingOrchestrationService).confirmBooking("booking-2");
    }

    @Test
    void queriesUsingTheConfiguredGracePeriodAndBatchSize() throws Exception {
        setField(job, "graceMinutes", 10L);
        setField(job, "batchSize", 50);
        when(bookingRepository.findDraftOlderThan(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.findPendingPaymentOlderThan(any(), anyInt())).thenReturn(List.of());

        job.reconcile();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(bookingRepository, times(1)).findDraftOlderThan(cutoffCaptor.capture(), eq(50));
        verify(bookingRepository, times(1)).findPendingPaymentOlderThan(any(), eq(50));
        Instant expectedCutoff = Instant.now().minus(10, ChronoUnit.MINUTES);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = BookingReconciliationJob.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
