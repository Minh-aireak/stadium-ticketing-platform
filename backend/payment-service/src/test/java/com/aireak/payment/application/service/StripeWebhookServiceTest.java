package com.aireak.payment.application.service;

import com.aireak.payment.application.port.in.command.StripeWebhookEventCommand;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.application.port.out.ProcessedWebhookEventRepository;
import com.aireak.payment.domain.exception.InvalidPaymentStatusException;
import com.aireak.payment.domain.model.Payment;
import com.aireak.payment.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceTest {

    @Mock
    private ProcessedWebhookEventRepository processedWebhookEventRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentSagaSteps sagaSteps;

    private StripeWebhookService service;

    private void newService() {
        service = new StripeWebhookService(processedWebhookEventRepository, paymentRepository, sagaSteps);
    }

    private Payment initiatedPayment(String paymentId) {
        return Payment.reconstitute(paymentId, "booking-1", "buyer@example.com", new BigDecimal("100.00"), "USD",
                PaymentStatus.INITIATED, null, null, Instant.now(), 0L);
    }

    @Test
    void skipsProcessingWhenEventAlreadyProcessed() {
        newService();
        when(processedWebhookEventRepository.existsByEventId("evt-1")).thenReturn(true);

        service.handle(new StripeWebhookEventCommand(
                "evt-1", "payment_intent.succeeded", "booking-1", "pi-1", null));

        verify(paymentRepository, never()).findByBookingId(anyString());
        verify(processedWebhookEventRepository, never()).markProcessed(anyString(), anyString());
    }

    @Test
    void marksPaymentSucceededAndRecordsEventAsProcessed() {
        newService();
        when(processedWebhookEventRepository.existsByEventId("evt-1")).thenReturn(false);
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.of(initiatedPayment("payment-1")));

        service.handle(new StripeWebhookEventCommand(
                "evt-1", "payment_intent.succeeded", "booking-1", "pi-1", null));

        verify(sagaSteps).markSucceeded("payment-1", "pi-1");
        verify(sagaSteps, never()).markFailed(any(), any());
        verify(processedWebhookEventRepository).markProcessed("evt-1", "payment_intent.succeeded");
    }

    @Test
    void overridesGatewayAmbiguousFailedPaymentToSucceededOnWebhookEvent() {
        newService();
        Payment ambiguousPayment = Payment.reconstitute("payment-1", "booking-1", "buyer@example.com", new BigDecimal("100.00"), "USD",
                PaymentStatus.FAILED, null, Payment.GATEWAY_AMBIGUOUS_PREFIX + "connection timeout", Instant.now(), 0L);
        when(processedWebhookEventRepository.existsByEventId("evt-1")).thenReturn(false);
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.of(ambiguousPayment));

        service.handle(new StripeWebhookEventCommand(
                "evt-1", "payment_intent.succeeded", "booking-1", "pi-1", null));

        verify(sagaSteps).markSucceeded("payment-1", "pi-1");
        verify(processedWebhookEventRepository).markProcessed("evt-1", "payment_intent.succeeded");
    }

    @Test
    void marksPaymentFailedAndRecordsEventAsProcessed() {
        newService();
        when(processedWebhookEventRepository.existsByEventId("evt-2")).thenReturn(false);
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.of(initiatedPayment("payment-1")));

        service.handle(new StripeWebhookEventCommand(
                "evt-2", "payment_intent.payment_failed", "booking-1", "pi-1", "card declined"));

        verify(sagaSteps).markFailed("payment-1", "card declined");
        verify(sagaSteps, never()).markSucceeded(any(), any());
        verify(processedWebhookEventRepository).markProcessed("evt-2", "payment_intent.payment_failed");
    }

    @Test
    void isANoOpWhenPaymentAlreadyInTerminalStateButStillRecordsEventAsProcessed() {
        newService();
        when(processedWebhookEventRepository.existsByEventId("evt-1")).thenReturn(false);
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.of(initiatedPayment("payment-1")));
        doThrow(new InvalidPaymentStatusException("already succeeded"))
                .when(sagaSteps).markSucceeded("payment-1", "pi-1");

        service.handle(new StripeWebhookEventCommand(
                "evt-1", "payment_intent.succeeded", "booking-1", "pi-1", null));

        verify(processedWebhookEventRepository).markProcessed("evt-1", "payment_intent.succeeded");
    }

    @Test
    void ignoresUnknownBookingIdButStillRecordsEventAsProcessed() {
        newService();
        when(processedWebhookEventRepository.existsByEventId("evt-1")).thenReturn(false);
        when(paymentRepository.findByBookingId("booking-1")).thenReturn(Optional.empty());

        service.handle(new StripeWebhookEventCommand(
                "evt-1", "payment_intent.succeeded", "booking-1", "pi-1", null));

        verify(sagaSteps, never()).markSucceeded(any(), any());
        verify(processedWebhookEventRepository).markProcessed("evt-1", "payment_intent.succeeded");
    }

    @Test
    void ignoresUnhandledEventTypeButStillRecordsEventAsProcessed() {
        newService();
        when(processedWebhookEventRepository.existsByEventId("evt-3")).thenReturn(false);

        service.handle(new StripeWebhookEventCommand(
                "evt-3", "payment_intent.created", "booking-1", "pi-1", null));

        verify(paymentRepository, never()).findByBookingId(anyString());
        verify(processedWebhookEventRepository).markProcessed("evt-3", "payment_intent.created");
    }
}
