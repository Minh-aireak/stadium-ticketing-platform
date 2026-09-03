package com.aireak.booking.adapter.in.scheduling;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.booking.application.service.BookingOrchestrationService.InventoryConfirmationOutcome;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.SeatSelection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This job had no tests at all, which is how it went unnoticed that it can never stop: it is the
 * only backstop for a booking that was paid for but whose seats were never marked SOLD, and until
 * {@code InventoryConfirmationRefusedException} existed every failure looked alike to it, so a
 * permanently-refused booking was re-asked every five minutes for the life of the row.
 *
 * <p>Its sibling {@code BookingReconciliationJobTest} has covered the payment-side reconciler
 * since that one was written.
 */
@ExtendWith(MockitoExtension.class)
class InventoryConfirmationReconcilerTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingOrchestrationService bookingOrchestrationService;

    private MeterRegistry meterRegistry;
    private InventoryConfirmationReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        reconciler = new InventoryConfirmationReconciler(
                bookingRepository, bookingOrchestrationService, meterRegistry);
        setField(reconciler, "graceMinutes", 5L);
        setField(reconciler, "batchSize", 200);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private static Booking confirmedAwaitingInventory(String bookingId) {
        return Booking.reconstitute(bookingId, "customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(List.of("A1")), BookingAmount.of(new BigDecimal("100.00"), "USD"),
                BookingStatus.CONFIRMED, Instant.now(), null, 0L, false, false);
    }

    @Test
    void queriesUsingTheConfiguredGracePeriodAndBatchSize() throws Exception {
        setField(reconciler, "graceMinutes", 12L);
        setField(reconciler, "batchSize", 25);
        when(bookingRepository.findConfirmedAwaitingInventoryConfirmation(any(), anyInt())).thenReturn(List.of());

        reconciler.reconcile();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(bookingRepository).findConfirmedAwaitingInventoryConfirmation(cutoff.capture(), eq(25));
        assertThat(cutoff.getValue())
                .isCloseTo(Instant.now().minus(12, ChronoUnit.MINUTES), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void retriesEveryBookingTheQueryReturns() {
        when(bookingRepository.findConfirmedAwaitingInventoryConfirmation(any(), anyInt()))
                .thenReturn(List.of(confirmedAwaitingInventory("b1"), confirmedAwaitingInventory("b2")));
        when(bookingOrchestrationService.retryInventoryConfirmation(any()))
                .thenReturn(InventoryConfirmationOutcome.CONFIRMED);

        reconciler.reconcile();

        verify(bookingOrchestrationService, times(2)).retryInventoryConfirmation(any());
    }

    /**
     * The gauge is the whole reason this job is now constructed with a MeterRegistry. A booking
     * that was paid for and whose seats were never sold is the mirror image of the failure
     * payment-service publishes as {@code payment.unreconciled.count}, and it had no equivalent:
     * booking-service registered no custom meters at all, so the only trace of it was a WARN line
     * that looks the same whether the backlog is draining or permanently stuck.
     */
    @Test
    void publishesHowManyBookingsAreStillWaitingOnTheirSeatSale() {
        when(bookingRepository.findConfirmedAwaitingInventoryConfirmation(any(), anyInt()))
                .thenReturn(List.of(confirmedAwaitingInventory("b1"), confirmedAwaitingInventory("b2")));
        when(bookingOrchestrationService.retryInventoryConfirmation(any()))
                .thenReturn(InventoryConfirmationOutcome.UNRESOLVED);
        when(bookingRepository.countInventorySaleRefused()).thenReturn(0L);

        reconciler.reconcile();

        assertThat(gauge("booking.inventory.unconfirmed.count")).isEqualTo(2.0);
        assertThat(gauge("booking.inventory.sale.refused.count")).isZero();
    }

    /** The one an operator is actually paged on: nothing will ever resolve these without them. */
    @Test
    void publishesHowManyBookingsWereRefusedForGood() {
        when(bookingRepository.findConfirmedAwaitingInventoryConfirmation(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.countInventorySaleRefused()).thenReturn(3L);

        reconciler.reconcile();

        assertThat(gauge("booking.inventory.sale.refused.count")).isEqualTo(3.0);
    }

    /**
     * Reset to zero on a clean scan, for the same reason UnreconciledPaymentAlertJob resets its
     * own: a backlog somebody has since resolved must not leave the alert firing forever.
     */
    @Test
    void aResolvedBacklogClearsTheGaugesInsteadOfLeavingTheAlertFiring() {
        when(bookingRepository.findConfirmedAwaitingInventoryConfirmation(any(), anyInt()))
                .thenReturn(List.of(confirmedAwaitingInventory("b1")))
                .thenReturn(List.of());
        when(bookingOrchestrationService.retryInventoryConfirmation(any()))
                .thenReturn(InventoryConfirmationOutcome.CONFIRMED);
        when(bookingRepository.countInventorySaleRefused()).thenReturn(1L).thenReturn(0L);

        reconciler.reconcile();
        assertThat(gauge("booking.inventory.unconfirmed.count")).isEqualTo(1.0);
        assertThat(gauge("booking.inventory.sale.refused.count")).isEqualTo(1.0);

        reconciler.reconcile();
        assertThat(gauge("booking.inventory.unconfirmed.count")).isZero();
        assertThat(gauge("booking.inventory.sale.refused.count")).isZero();
    }

    /**
     * An empty scan still has to publish, and this is where the payment-side precedent would have
     * been easy to get wrong: {@code findConfirmedAwaitingInventoryConfirmation} returning nothing
     * used to return early, so on the day the backlog cleared the gauge would have kept reporting
     * whatever the last non-empty run saw.
     */
    @Test
    void anEmptyScanStillPublishesBothGauges() {
        when(bookingRepository.findConfirmedAwaitingInventoryConfirmation(any(), anyInt())).thenReturn(List.of());
        when(bookingRepository.countInventorySaleRefused()).thenReturn(0L);

        reconciler.reconcile();

        assertThat(gauge("booking.inventory.unconfirmed.count")).isZero();
        assertThat(gauge("booking.inventory.sale.refused.count")).isZero();
    }

    /**
     * One booking must not be able to stop the batch. retryInventoryConfirmation does not throw
     * today -- BookingOrchestrationService#confirmInventoryReservation catches Exception on every
     * path -- which is precisely why this is worth pinning: the guard is invisible until an edit
     * removes that catch, and by then the failure is one bad booking silently stranding every
     * booking behind it in the list.
     */
    @Test
    void oneBookingThatBlowsUpDoesNotStrandTheRestOfTheBatch() {
        when(bookingRepository.findConfirmedAwaitingInventoryConfirmation(any(), anyInt()))
                .thenReturn(List.of(confirmedAwaitingInventory("b1"), confirmedAwaitingInventory("b2")));
        when(bookingOrchestrationService.retryInventoryConfirmation(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(InventoryConfirmationOutcome.CONFIRMED);
        when(bookingRepository.countInventorySaleRefused()).thenReturn(0L);

        assertThatCode(() -> reconciler.reconcile()).doesNotThrowAnyException();

        verify(bookingOrchestrationService, times(2)).retryInventoryConfirmation(any());
        // The one that blew up is not counted as resolved.
        assertThat(gauge("booking.inventory.unconfirmed.count")).isEqualTo(2.0);
    }
}
