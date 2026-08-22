package com.aireak.payment.adapter.in.messaging;

import com.aireak.booking.domain.event.RefundRequestedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.payment.application.port.in.RefundPaymentUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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

    private static EventEnvelope<?> envelopeFor(String bookingId, String reason) {
        return EventEnvelope.of(KafkaTopics.REFUND_REQUESTED,
                new RefundRequestedEvent(bookingId, reason, Instant.now()), "trace-1");
    }

    @Test
    void appliesTheRefundForTheBookingNamedInTheEvent() {
        when(refundPaymentUseCase.refundByBookingId("booking-1", "Match cancelled"))
                .thenReturn(Optional.of("payment-1"));

        consumer().consume(envelopeFor("booking-1", "Match cancelled"));

        verify(refundPaymentUseCase).refundByBookingId("booking-1", "Match cancelled");
    }

    /**
     * A redelivery finds the payment already REFUNDED and gets an empty Optional back. That is a
     * normal outcome, not a failure — throwing here would dead-letter a request that was in fact
     * already honoured.
     */
    @Test
    void treatsNothingToRefundAsSuccessSoARedeliveryIsNotDeadLettered() {
        when(refundPaymentUseCase.refundByBookingId(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatCode(() -> consumer().consume(envelopeFor("booking-1", "Match cancelled")))
                .doesNotThrowAnyException();
    }

    /**
     * The whole point of the migration: a failed refund must reach the error handler (retry, then
     * the dead-letter topic and its ERROR alert) rather than being logged and forgotten.
     */
    @Test
    void letsAFailedRefundPropagateInsteadOfSwallowingIt() {
        when(refundPaymentUseCase.refundByBookingId(anyString(), anyString()))
                .thenThrow(new RuntimeException("gateway refused the refund"));

        assertThatThrownBy(() -> consumer().consume(envelopeFor("booking-1", "Match cancelled")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("gateway refused the refund");
    }

    @Test
    void ignoresAnEnvelopeCarryingSomeOtherPayload() {
        consumer().consume(EventEnvelope.of(KafkaTopics.REFUND_REQUESTED, "not an event", "trace-1"));

        verify(refundPaymentUseCase, never()).refundByBookingId(anyString(), anyString());
    }
}
