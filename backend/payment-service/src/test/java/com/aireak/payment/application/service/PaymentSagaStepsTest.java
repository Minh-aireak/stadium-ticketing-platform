package com.aireak.payment.application.service;

import com.aireak.payment.application.port.out.DomainEventPublisher;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import com.aireak.payment.domain.model.Payment;
import com.aireak.payment.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSagaStepsTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private DomainEventPublisher eventPublisher;

    private PaymentSagaSteps sagaSteps;

    private void newSagaSteps() {
        sagaSteps = new PaymentSagaSteps(paymentRepository, eventPublisher);
    }

    @Test
    void tryInitiateReturnsCreatedWhenInsertSucceeds() {
        newSagaSteps();
        when(paymentRepository.tryInsert(any(Payment.class))).thenReturn(true);

        PaymentSagaSteps.InitiateOutcome outcome =
                sagaSteps.tryInitiate("booking-1", new BigDecimal("100.00"), "USD");

        assertThat(outcome).isInstanceOf(PaymentSagaSteps.InitiateOutcome.Created.class);
        assertThat(((PaymentSagaSteps.InitiateOutcome.Created) outcome).paymentId()).isNotBlank();
    }

    @Test
    void tryInitiateReturnsAlreadyExistsWhenInsertFails() {
        newSagaSteps();
        when(paymentRepository.tryInsert(any(Payment.class))).thenReturn(false);

        PaymentSagaSteps.InitiateOutcome outcome =
                sagaSteps.tryInitiate("booking-1", new BigDecimal("100.00"), "USD");

        assertThat(outcome).isInstanceOf(PaymentSagaSteps.InitiateOutcome.AlreadyExists.class);
    }

    @Test
    void markSucceededSavesAndPublishesPaymentSucceededEvent() {
        newSagaSteps();
        Payment existing = Payment.reconstitute("payment-1", "booking-1", new BigDecimal("100.00"),
                "USD", PaymentStatus.INITIATED, null, null, Instant.now(), 0L);
        when(paymentRepository.findById("payment-1")).thenReturn(Optional.of(existing));

        sagaSteps.markSucceeded("payment-1", "gw-tx-1");

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(saved.getValue().getGatewayTransactionId()).isEqualTo("gw-tx-1");
        // The saved entity must be the SAME reconstituted instance so its carried-through
        // @Version survives into the JPA write (see Payment.version javadoc) — a saga step
        // that instead built a fresh Payment would silently reintroduce the duplicate-PK bug.
        assertThat(saved.getValue()).isSameAs(existing);

        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue()).hasSize(1);
        assertThat(published.getValue().get(0)).isInstanceOf(PaymentSucceededEvent.class);
    }

    @Test
    void markFailedSavesAndPublishesPaymentFailedEvent() {
        newSagaSteps();
        Payment existing = Payment.reconstitute("payment-1", "booking-1", new BigDecimal("100.00"),
                "USD", PaymentStatus.INITIATED, null, null, Instant.now(), 0L);
        when(paymentRepository.findById("payment-1")).thenReturn(Optional.of(existing));

        sagaSteps.markFailed("payment-1", "gateway timeout");

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);

        ArgumentCaptor<List<Object>> published = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(published.capture());
        assertThat(published.getValue().get(0)).isInstanceOf(PaymentFailedEvent.class);
    }

    @Test
    void markSucceededThrowsWhenPaymentNotFound() {
        newSagaSteps();
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sagaSteps.markSucceeded("missing", "gw-tx-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markFailedThrowsWhenPaymentNotFound() {
        newSagaSteps();
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sagaSteps.markFailed("missing", "reason"))
                .isInstanceOf(IllegalStateException.class);
    }
}
