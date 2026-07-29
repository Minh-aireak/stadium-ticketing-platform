package com.aireak.payment.application.service;

import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.aireak.payment.application.port.out.PaymentIdempotencyPort;
import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.exception.DuplicatePaymentException;
import com.aireak.payment.domain.model.Payment;
import com.aireak.payment.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the payment orchestrator. {@link PaymentSagaSteps} is mocked — this only
 * exercises {@link PaymentService}'s own control flow (idempotency short-circuits, gateway
 * success/failure branching), not the saga step's persistence/publishing behavior (see
 * {@link PaymentSagaStepsTest} for that).
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final InitiatePaymentCommand COMMAND =
            new InitiatePaymentCommand("booking-1", new BigDecimal("100.00"), "USD");

    @Mock
    private PaymentSagaSteps sagaSteps;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGatewayPort paymentGatewayPort;
    @Mock
    private PaymentIdempotencyPort idempotencyPort;
    @Mock
    private PaymentReconciliationPort reconciliationPort;

    private PaymentService service;

    private void newService() {
        service = new PaymentService(
                sagaSteps, paymentRepository, paymentGatewayPort, idempotencyPort, reconciliationPort);
    }

    private Payment existingPayment(String paymentId) {
        return Payment.reconstitute(paymentId, "booking-1", new BigDecimal("100.00"), "USD",
                PaymentStatus.INITIATED, null, null, Instant.now(), 0L);
    }

    @Test
    void executeReturnsExistingPaymentWithoutTouchingSagaStepsWhenIdempotencyGuardAlreadyHeld() {
        newService();
        when(idempotencyPort.tryAcquire(anyString(), any(Duration.class))).thenReturn(false);
        when(paymentRepository.findByBookingId("booking-1"))
                .thenReturn(Optional.of(existingPayment("payment-1")));

        String paymentId = service.execute(COMMAND);

        assertThat(paymentId).isEqualTo("payment-1");
        verify(sagaSteps, never()).tryInitiate(any(), any(), any());
        verify(paymentGatewayPort, never()).charge(any(), any(), any());
    }

    @Test
    void executeThrowsWhenIdempotencyGuardHeldButNoExistingPaymentFound() {
        newService();
        when(idempotencyPort.tryAcquire(anyString(), any(Duration.class))).thenReturn(false);
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(COMMAND))
                .isInstanceOf(DuplicatePaymentException.class);
    }

    @Test
    void executeReturnsExistingPaymentWhenSagaStepsReportsAlreadyExists() {
        newService();
        when(idempotencyPort.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(sagaSteps.tryInitiate(eq("booking-1"), eq(COMMAND.amount()), eq(COMMAND.currency())))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.AlreadyExists());
        when(paymentRepository.findByBookingId("booking-1"))
                .thenReturn(Optional.of(existingPayment("payment-1")));

        String paymentId = service.execute(COMMAND);

        assertThat(paymentId).isEqualTo("payment-1");
        verify(paymentGatewayPort, never()).charge(any(), any(), any());
    }

    @Test
    void executeChargesGatewayAndMarksSucceededOnSuccess() {
        newService();
        when(idempotencyPort.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(sagaSteps.tryInitiate(eq("booking-1"), eq(COMMAND.amount()), eq(COMMAND.currency())))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-1"));
        when(paymentGatewayPort.charge("booking-1", COMMAND.amount(), COMMAND.currency()))
                .thenReturn("gw-tx-1");

        String paymentId = service.execute(COMMAND);

        assertThat(paymentId).isEqualTo("payment-1");
        verify(sagaSteps).markSucceeded("payment-1", "gw-tx-1");
        verify(sagaSteps, never()).markFailed(any(), any());
        verify(reconciliationPort, never()).recordUnpersistedSuccess(any(), any(), any(), any(), any(), any());
    }

    @Test
    void executeRetriesPersistingSucceededOutcomeAndStopsOnceItSucceeds() {
        newService();
        when(idempotencyPort.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(sagaSteps.tryInitiate(eq("booking-1"), eq(COMMAND.amount()), eq(COMMAND.currency())))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-1"));
        when(paymentGatewayPort.charge("booking-1", COMMAND.amount(), COMMAND.currency()))
                .thenReturn("gw-tx-1");
        doThrow(new RuntimeException("transient db error"))
                .doNothing()
                .when(sagaSteps).markSucceeded("payment-1", "gw-tx-1");

        String paymentId = service.execute(COMMAND);

        assertThat(paymentId).isEqualTo("payment-1");
        verify(sagaSteps, times(2)).markSucceeded("payment-1", "gw-tx-1");
        verify(sagaSteps, never()).markFailed(any(), any());
        verify(reconciliationPort, never()).recordUnpersistedSuccess(any(), any(), any(), any(), any(), any());
    }

    @Test
    void executeRecordsForManualReconciliationInsteadOfMarkingFailedWhenPersistingSucceededOutcomeKeepsFailing() {
        newService();
        when(idempotencyPort.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(sagaSteps.tryInitiate(eq("booking-1"), eq(COMMAND.amount()), eq(COMMAND.currency())))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-1"));
        when(paymentGatewayPort.charge("booking-1", COMMAND.amount(), COMMAND.currency()))
                .thenReturn("gw-tx-1");
        doThrow(new RuntimeException("db down"))
                .when(sagaSteps).markSucceeded("payment-1", "gw-tx-1");

        // The gateway already charged the customer here — this must NOT throw out of execute(),
        // and must NEVER fall through to markFailed() (that would record a real charge as FAILED).
        String paymentId = service.execute(COMMAND);

        assertThat(paymentId).isEqualTo("payment-1");
        verify(sagaSteps, times(3)).markSucceeded("payment-1", "gw-tx-1");
        verify(sagaSteps, never()).markFailed(any(), any());
        verify(reconciliationPort).recordUnpersistedSuccess(
                eq("payment-1"), eq("booking-1"), eq("gw-tx-1"), eq(COMMAND.amount()), eq(COMMAND.currency()), any());
    }

    @Test
    void executeSwallowsAFailureRecordingForManualReconciliationInsteadOfThrowing() {
        newService();
        when(idempotencyPort.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(sagaSteps.tryInitiate(eq("booking-1"), eq(COMMAND.amount()), eq(COMMAND.currency())))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-1"));
        when(paymentGatewayPort.charge("booking-1", COMMAND.amount(), COMMAND.currency()))
                .thenReturn("gw-tx-1");
        doThrow(new RuntimeException("db down")).when(sagaSteps).markSucceeded("payment-1", "gw-tx-1");
        doThrow(new RuntimeException("reconciliation table unreachable too"))
                .when(reconciliationPort).recordUnpersistedSuccess(any(), any(), any(), any(), any(), any());

        // Even the last-resort durable write failing must not propagate — there is nothing left
        // to compensate with at this point, only logging.
        String paymentId = service.execute(COMMAND);

        assertThat(paymentId).isEqualTo("payment-1");
    }

    @Test
    void executeMarksFailedInsteadOfPropagatingWhenGatewayThrows() {
        newService();
        when(idempotencyPort.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(sagaSteps.tryInitiate(eq("booking-1"), eq(COMMAND.amount()), eq(COMMAND.currency())))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-1"));
        when(paymentGatewayPort.charge(any(), any(), any()))
                .thenThrow(new RuntimeException("gateway unreachable"));

        // The gateway failure must NOT propagate out of execute() — it's translated into a
        // FAILED payment so callers get a paymentId to poll, not an exception.
        String paymentId = service.execute(COMMAND);

        assertThat(paymentId).isEqualTo("payment-1");
        verify(sagaSteps).markFailed(eq("payment-1"), anyString());
        verify(sagaSteps, never()).markSucceeded(any(), any());
    }

    @Test
    void getByBookingIdDelegatesToRepository() {
        newService();
        Payment payment = existingPayment("payment-1");
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.of(payment));

        Optional<Payment> result = service.getByBookingId("booking-1");

        assertThat(result).contains(payment);
    }
}
