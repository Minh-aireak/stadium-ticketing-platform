package com.aireak.payment.adapter.in.messaging;

import com.aireak.booking.domain.event.RefundRequestedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.payment.application.port.in.RefundCommand;
import com.aireak.payment.application.port.in.RefundPaymentUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This consumer replaced a synchronous HTTP call whose circuit-breaker fallback swallowed failures,
 * losing refunds silently. The behaviour that matters here is therefore as much about what it must
 * NOT do — never swallow — as about the happy path.
 */
@ExtendWith(MockitoExtension.class)
class RefundRequestedConsumerTest {

    @Mock
    private RefundPaymentUseCase refundPaymentUseCase;

    private RefundRequestedConsumer consumer() {
        return new RefundRequestedConsumer(refundPaymentUseCase);
    }

    private static EventEnvelope<?> envelopeFor(RefundRequestedEvent event) {
        return EventEnvelope.of(KafkaTopics.REFUND_REQUESTED, event, "trace-1");
    }

    private static RefundRequestedEvent remainingBalance(String bookingId, String reason) {
        return new RefundRequestedEvent(bookingId, "req-1", null, null, List.of(), reason, Instant.now());
    }

    @Test
    void appliesTheRefundForTheBookingNamedInTheEvent() {
        RefundCommand command = new RefundCommand("booking-1", "req-1", null, "Match cancelled");
        when(refundPaymentUseCase.refund(command)).thenReturn(Optional.of("payment-1"));

        consumer().consume(envelopeFor(remainingBalance("booking-1", "Match cancelled")));

        verify(refundPaymentUseCase).refund(command);
    }

    /** A cancelled seat: its price and the request's own id reach the use case unchanged. */
    @Test
    void passesThePartialAmountAndTheRequestIdThrough() {
        RefundRequestedEvent seatRefund = new RefundRequestedEvent("booking-1", "req-7", new BigDecimal("150000"), "VND",
                List.of("A2"), "Cancelled by the customer", Instant.now());
        RefundCommand command = new RefundCommand("booking-1", "req-7", new BigDecimal("150000"), "Cancelled by the customer");
        when(refundPaymentUseCase.refund(command)).thenReturn(Optional.of("payment-1"));

        consumer().consume(envelopeFor(seatRefund));

        verify(refundPaymentUseCase).refund(command);
    }

    /**
     * A redelivery finds the request already applied and gets an empty Optional back. That is a
     * normal outcome, not a failure — throwing here would dead-letter a request that was in fact
     * already honoured.
     */
    @Test
    void treatsNothingToRefundAsSuccessSoARedeliveryIsNotDeadLettered() {
        when(refundPaymentUseCase.refund(any(RefundCommand.class))).thenReturn(Optional.empty());

        assertThatCode(() -> consumer().consume(envelopeFor(remainingBalance("booking-1", "Match cancelled"))))
                .doesNotThrowAnyException();
    }

    /**
     * The whole point of the migration: a failed refund must reach the error handler (retry, then
     * the dead-letter topic and its ERROR alert) rather than being logged and forgotten.
     */
    @Test
    void letsAFailedRefundPropagateInsteadOfSwallowingIt() {
        when(refundPaymentUseCase.refund(any(RefundCommand.class)))
                .thenThrow(new RuntimeException("gateway refused the refund"));

        assertThatThrownBy(() -> consumer().consume(envelopeFor(remainingBalance("booking-1", "Match cancelled"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("gateway refused the refund");
    }

    @Test
    void ignoresAnEnvelopeCarryingSomeOtherPayload() {
        consumer().consume(EventEnvelope.of(KafkaTopics.REFUND_REQUESTED, "not an event", "trace-1"));

        verify(refundPaymentUseCase, never()).refund(any(RefundCommand.class));
    }
}
