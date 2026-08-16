package com.aireak.payment.domain.model;

import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentInitiatedEvent;
import com.aireak.payment.domain.event.PaymentRefundedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import com.aireak.payment.domain.exception.InvalidPaymentStatusException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final BigDecimal AMOUNT = new BigDecimal("150.00");

    @Test
    void initiateStartsInInitiatedWithNullVersionAndRaisesEvent() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getVersion()).isNull();
        assertThat(payment.getPaymentId()).isNotBlank();
        assertThat(payment.getBookingId()).isEqualTo("booking-1");

        List<Object> events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(PaymentInitiatedEvent.class);
        assertThat(((PaymentInitiatedEvent) events.get(0)).paymentId()).isEqualTo(payment.getPaymentId());
    }

    /** notification-service reads this field off the event to address the receipt email. */
    @Test
    void markSucceededCarriesTheCustomerEmailCapturedAtInitiationOntoTheEvent() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.pullDomainEvents();

        payment.markSucceeded("gw-tx-1");

        PaymentSucceededEvent event = (PaymentSucceededEvent) payment.pullDomainEvents().get(0);
        assertThat(event.customerEmail()).isEqualTo("buyer@example.com");
        assertThat(event.bookingId()).isEqualTo("booking-1");
        assertThat(event.amount()).isEqualTo(AMOUNT);
        assertThat(event.currency()).isEqualTo("USD");
    }

    /** Internal-service initiations have no end-user identity — the event must still be valid. */
    @Test
    void markSucceededWithoutACustomerEmailStillRaisesTheEvent() {
        Payment payment = Payment.initiate("booking-1", null, AMOUNT, "USD");
        payment.pullDomainEvents();

        payment.markSucceeded("gw-tx-1");

        PaymentSucceededEvent event = (PaymentSucceededEvent) payment.pullDomainEvents().get(0);
        assertThat(event.customerEmail()).isNull();
    }

    @Test
    void markSucceededTransitionsFromInitiatedAndRaisesEvent() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.pullDomainEvents(); // discard PaymentInitiatedEvent

        payment.markSucceeded("gw-tx-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getGatewayTransactionId()).isEqualTo("gw-tx-1");
        List<Object> events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(PaymentSucceededEvent.class);
        assertThat(((PaymentSucceededEvent) events.get(0)).gatewayTransactionId()).isEqualTo("gw-tx-1");
    }

    @Test
    void markSucceededRejectsWhenAlreadySucceeded() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markSucceeded("gw-tx-1");

        assertThatThrownBy(() -> payment.markSucceeded("gw-tx-2"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void markSucceededRejectsWhenAlreadyFailedWithDefiniteDecline() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markFailed("card declined"); // non-ambiguous decline

        assertThat(payment.isAmbiguousFailure()).isFalse();
        assertThatThrownBy(() -> payment.markSucceeded("gw-tx-1"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void markSucceededAllowsTransitionFromGatewayAmbiguousFailedState() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markFailedAmbiguous("connection reset by peer");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.isAmbiguousFailure()).isTrue();
        assertThat(payment.getFailureReason()).startsWith(Payment.GATEWAY_AMBIGUOUS_PREFIX);

        payment.pullDomainEvents(); // clear previous events
        payment.markSucceeded("gw-tx-webhook");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getGatewayTransactionId()).isEqualTo("gw-tx-webhook");

        List<Object> events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(PaymentSucceededEvent.class);
        assertThat(((PaymentSucceededEvent) events.get(0)).gatewayTransactionId()).isEqualTo("gw-tx-webhook");
    }

    @Test
    void markFailedTransitionsFromInitiatedAndRaisesEvent() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.pullDomainEvents(); // discard PaymentInitiatedEvent

        payment.markFailed("gateway timeout");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("gateway timeout");
        List<Object> events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(PaymentFailedEvent.class);
        assertThat(((PaymentFailedEvent) events.get(0)).reason()).isEqualTo("gateway timeout");
    }

    @Test
    void markFailedRejectsWhenAlreadySucceeded() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markSucceeded("gw-tx-1");

        assertThatThrownBy(() -> payment.markFailed("late failure"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void retryTransitionsFromFailedToInitiatedAndClearsFailureReason() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markFailed("gateway timeout");

        payment.retry();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getFailureReason()).isNull();
    }

    @Test
    void retryRejectsWhenNotFailed() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        assertThatThrownBy(payment::retry)
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void retryRejectsWhenAlreadySucceeded() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markSucceeded("gw-tx-1");

        assertThatThrownBy(payment::retry)
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void refundTransitionsFromSucceededAndRaisesEvent() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markSucceeded("gw-tx-1");
        payment.pullDomainEvents(); // discard PaymentInitiatedEvent + PaymentSucceededEvent

        payment.refund("gw-refund-1", "Match cancelled");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        List<Object> events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        PaymentRefundedEvent event = (PaymentRefundedEvent) events.get(0);
        assertThat(event.gatewayRefundId()).isEqualTo("gw-refund-1");
        assertThat(event.reason()).isEqualTo("Match cancelled");
        assertThat(event.amount()).isEqualTo(AMOUNT);
    }

    @Test
    void refundRejectsWhenNotSucceeded() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        assertThatThrownBy(() -> payment.refund("gw-refund-1", "Match cancelled"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void refundRejectsWhenAlreadyRefunded() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markSucceeded("gw-tx-1");
        payment.refund("gw-refund-1", "first refund");

        assertThatThrownBy(() -> payment.refund("gw-refund-2", "second refund"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void reconstitutePreservesVersionStatusAndRaisesNoEvents() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");

        Payment payment = Payment.reconstitute("payment-1", "booking-1", "buyer@example.com", AMOUNT, "USD",
                PaymentStatus.SUCCEEDED, "gw-tx-1", null, createdAt, 3L);

        assertThat(payment.getVersion()).isEqualTo(3L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getGatewayTransactionId()).isEqualTo("gw-tx-1");
        assertThat(payment.getCreatedAt()).isEqualTo(createdAt);
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    void reconstituteWithNullVersionRepresentsAPreExistingRowLoadedBeforeFirstSave() {
        // version is only ever null for an in-memory initiate()-d payment; reconstitute() just
        // carries whatever persistence handed it through unchanged (see Payment.version javadoc).
        Payment payment = Payment.reconstitute("payment-1", "booking-1", "buyer@example.com", AMOUNT, "USD",
                PaymentStatus.INITIATED, null, null, Instant.now(), 0L);

        assertThat(payment.getVersion()).isEqualTo(0L);
    }

    @Test
    void pullDomainEventsClearsTheList() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        List<Object> firstPull = payment.pullDomainEvents();
        List<Object> secondPull = payment.pullDomainEvents();

        assertThat(firstPull).hasSize(1);
        assertThat(secondPull).isEmpty();
    }
}
