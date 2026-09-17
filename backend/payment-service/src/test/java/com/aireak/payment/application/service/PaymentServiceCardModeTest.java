package com.aireak.payment.application.service;

import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.application.port.out.PaymentDeclinedException;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.aireak.payment.application.port.out.PaymentGatewayPort.IntentHandle;
import com.aireak.payment.application.port.out.PaymentGatewayPort.IntentOutcome;
import com.aireak.payment.application.port.out.PaymentGatewayPort.IntentSnapshot;
import com.aireak.payment.application.port.out.PaymentIdempotencyPort;
import com.aireak.payment.application.port.out.PaymentIdempotencyResult;
import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.config.PaymentModeProperties;
import com.aireak.payment.domain.exception.InvalidPaymentStatusException;
import com.aireak.payment.domain.model.Payment;
import com.aireak.payment.domain.model.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card mode ({@code payment.mode=card}): the service opens an intent and stops; the outcome is
 * recorded later by whichever of sync, webhook or expiry learns it first. What these tests pin is
 * that none of those three ever moves money or a booking on the browser's say-so, and that the
 * boundary with auto mode is exactly {@code charge()} never being called.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceCardModeTest {

    private static final InitiatePaymentCommand COMMAND =
            new InitiatePaymentCommand("booking-1", "buyer@example.com", new BigDecimal("660000"), "VND");
    private static final PaymentModeProperties CARD_MODE = new PaymentModeProperties(
            PaymentModeProperties.Mode.CARD,
            new PaymentModeProperties.Card(8, new PaymentModeProperties.ExpiryJob(30_000, 50)));

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

    @BeforeEach
    void newService() {
        service = new PaymentService(
                sagaSteps, paymentRepository, paymentGatewayPort, idempotencyPort, reconciliationPort, CARD_MODE);
    }

    private static Payment cardPayment(String paymentId, PaymentStatus status, String failureReason) {
        return Payment.reconstitute(paymentId, "booking-1", "buyer@example.com", new BigDecimal("660000"), "VND",
                status, null, failureReason, Instant.now(), 0, 0L, "pi_1", "pi_1_secret_x");
    }

    private void freshInitiate() {
        when(idempotencyPort.acquire(anyString())).thenReturn(new PaymentIdempotencyResult.Acquired());
        when(sagaSteps.tryInitiate("booking-1", COMMAND.customerEmail(), COMMAND.amount(), COMMAND.currency()))
                .thenReturn(new PaymentSagaSteps.InitiateOutcome.Created("payment-1"));
    }

    // ---- execute ----

    @Test
    void executeOpensAnIntentAndAttachesItInsteadOfCharging() {
        freshInitiate();
        when(paymentGatewayPort.createIntent("booking-1", "booking-1", COMMAND.amount(), COMMAND.currency()))
                .thenReturn(new IntentHandle("pi_1", "pi_1_secret_x"));

        String paymentId = service.execute(COMMAND);

        assertThat(paymentId).isEqualTo("payment-1");
        verify(sagaSteps).attachIntent("payment-1", "pi_1", "pi_1_secret_x");
        verify(paymentGatewayPort, never()).charge(any(), any(), any(), any());
        verify(sagaSteps, never()).markSucceeded(any(), any());
        verify(sagaSteps, never()).markFailed(any(), any());
    }

    @Test
    void executeMarksFailedWhenTheGatewayRejectsTheIntent() {
        freshInitiate();
        when(paymentGatewayPort.createIntent(any(), any(), any(), any()))
                .thenThrow(new PaymentDeclinedException("amount too small"));

        service.execute(COMMAND);

        // Nothing was charged and nothing is confirmable, so the booking may be cancelled at once.
        verify(sagaSteps).markFailed("payment-1", "amount too small");
        verify(sagaSteps, never()).attachIntent(any(), any(), any());
    }

    @Test
    void executeClosesTheIntentAgainWhenItCannotBePersisted() {
        freshInitiate();
        when(paymentGatewayPort.createIntent(any(), any(), any(), any()))
                .thenReturn(new IntentHandle("pi_1", "pi_1_secret_x"));
        doThrow(new RuntimeException("db down")).when(sagaSteps).attachIntent("payment-1", "pi_1", "pi_1_secret_x");

        service.execute(COMMAND);

        // An intent nobody can find again must not stay confirmable at the gateway.
        verify(paymentGatewayPort).cancelIntent("pi_1");
        verify(sagaSteps).markFailedAmbiguous(eq("payment-1"), contains("db down"));
    }

    // ---- syncWithGateway ----

    @Test
    void syncRecordsASucceededIntentThroughTheSameStepAsAnAutoModeCharge() {
        Payment open = cardPayment("payment-1", PaymentStatus.INITIATED, null);
        Payment done = cardPayment("payment-1", PaymentStatus.SUCCEEDED, null);
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.of(open), Optional.of(done));
        when(paymentGatewayPort.retrieveIntent("pi_1")).thenReturn(new IntentSnapshot(IntentOutcome.SUCCEEDED, null));

        Optional<Payment> result = service.syncWithGateway("booking-1");

        verify(sagaSteps).markSucceeded("payment-1", "pi_1");
        assertThat(result).map(Payment::getStatus).contains(PaymentStatus.SUCCEEDED);
    }

    @Test
    void syncKeepsADeclinedAttemptOpenAndOnlyNotesTheReason() {
        when(paymentRepository.findByBookingId("booking-1"))
                .thenReturn(Optional.of(cardPayment("payment-1", PaymentStatus.INITIATED, null)));
        when(paymentGatewayPort.retrieveIntent("pi_1"))
                .thenReturn(new IntentSnapshot(IntentOutcome.OPEN, "Your card was declined."));

        service.syncWithGateway("booking-1");

        // The customer is still at the form with another card in hand: no FAILED, no event, no
        // cancelled booking -- just the reason the status endpoint can show.
        verify(sagaSteps).noteAttemptFailure("payment-1", "Your card was declined.");
        verify(sagaSteps, never()).markFailed(any(), any());
        verify(sagaSteps, never()).markSucceeded(any(), any());
    }

    @Test
    void syncDoesNotRepeatAReasonAlreadyNoted() {
        when(paymentRepository.findByBookingId("booking-1"))
                .thenReturn(Optional.of(cardPayment("payment-1", PaymentStatus.INITIATED, "Your card was declined.")));
        when(paymentGatewayPort.retrieveIntent("pi_1"))
                .thenReturn(new IntentSnapshot(IntentOutcome.OPEN, "Your card was declined."));

        service.syncWithGateway("booking-1");

        verify(sagaSteps, never()).noteAttemptFailure(any(), any());
    }

    @Test
    void syncIsANoOpForAnAutoModePaymentAndForATerminalOne() {
        Payment autoMode = Payment.reconstitute("payment-1", "booking-1", "buyer@example.com",
                new BigDecimal("660000"), "VND", PaymentStatus.INITIATED, null, null, Instant.now(), 0, 0L);
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.of(autoMode));
        service.syncWithGateway("booking-1");

        when(paymentRepository.findByBookingId("booking-2"))
                .thenReturn(Optional.of(cardPayment("payment-2", PaymentStatus.SUCCEEDED, null)));
        service.syncWithGateway("booking-2");

        verify(paymentGatewayPort, never()).retrieveIntent(any());
    }

    @Test
    void syncTreatsASuccessAlreadyRecordedByTheWebhookAsDoneNotAsUnreconciled() {
        when(paymentRepository.findByBookingId("booking-1"))
                .thenReturn(Optional.of(cardPayment("payment-1", PaymentStatus.INITIATED, null)));
        when(paymentGatewayPort.retrieveIntent("pi_1")).thenReturn(new IntentSnapshot(IntentOutcome.SUCCEEDED, null));
        // The webhook won the race between retrieve and markSucceeded.
        doThrow(new InvalidPaymentStatusException("requires INITIATED, current: SUCCEEDED"))
                .when(sagaSteps).markSucceeded("payment-1", "pi_1");

        service.syncWithGateway("booking-1");

        verify(reconciliationPort, never()).recordUnpersistedSuccess(any(), any(), any(), any(), any(), any());
    }

    // ---- closeExpiredWindows ----

    @Test
    void expiryCancelsOpenIntentsAndFailsThePaymentWithTheWindowAsReason() {
        Instant cutoff = Instant.now().minusSeconds(600);
        when(paymentRepository.findOpenCardPaymentsCreatedBefore(cutoff, 50))
                .thenReturn(List.of(cardPayment("payment-1", PaymentStatus.INITIATED, null)));
        when(paymentGatewayPort.cancelIntent("pi_1")).thenReturn(new IntentSnapshot(IntentOutcome.CANCELED, null));

        int closed = service.closeExpiredWindows(cutoff, 50);

        assertThat(closed).isEqualTo(1);
        verify(sagaSteps).markFailed("payment-1", "Payment window of 8 minutes expired");
    }

    @Test
    void expiryRecordsASuccessTheGatewayRefusedToCancel() {
        Instant cutoff = Instant.now().minusSeconds(600);
        when(paymentRepository.findOpenCardPaymentsCreatedBefore(cutoff, 50))
                .thenReturn(List.of(cardPayment("payment-1", PaymentStatus.INITIATED, null)));
        // The customer confirmed in the last second; Stripe will not cancel a succeeded intent.
        when(paymentGatewayPort.cancelIntent("pi_1")).thenReturn(new IntentSnapshot(IntentOutcome.SUCCEEDED, null));

        int closed = service.closeExpiredWindows(cutoff, 50);

        assertThat(closed).isZero();
        verify(sagaSteps).markSucceeded("payment-1", "pi_1");
        verify(sagaSteps, never()).markFailed(any(), any());
    }

    @Test
    void expiryLeavesAProcessingIntentForTheNextRunAndSurvivesOneGatewayFailure() {
        Instant cutoff = Instant.now().minusSeconds(600);
        when(paymentRepository.findOpenCardPaymentsCreatedBefore(eq(cutoff), anyInt())).thenReturn(List.of(
                cardPayment("payment-1", PaymentStatus.INITIATED, null),
                Payment.reconstitute("payment-2", "booking-2", null, new BigDecimal("1"), "VND",
                        PaymentStatus.INITIATED, null, null, Instant.now(), 0, 0L, "pi_2", "pi_2_secret"),
                Payment.reconstitute("payment-3", "booking-3", null, new BigDecimal("1"), "VND",
                        PaymentStatus.INITIATED, null, null, Instant.now(), 0, 0L, "pi_3", "pi_3_secret")));
        when(paymentGatewayPort.cancelIntent("pi_1")).thenReturn(new IntentSnapshot(IntentOutcome.PROCESSING, null));
        when(paymentGatewayPort.cancelIntent("pi_2")).thenThrow(new RuntimeException("Payment gateway unavailable"));
        when(paymentGatewayPort.cancelIntent("pi_3")).thenReturn(new IntentSnapshot(IntentOutcome.CANCELED, null));

        int closed = service.closeExpiredWindows(cutoff, 50);

        // Only the third actually expired; the first is the bank's to decide and the second is
        // retried next run. Neither stopped the batch.
        assertThat(closed).isEqualTo(1);
        verify(sagaSteps, never()).markFailed(eq("payment-1"), any());
        verify(sagaSteps, never()).markFailed(eq("payment-2"), any());
        verify(sagaSteps).markFailed(eq("payment-3"), any());
    }
}
