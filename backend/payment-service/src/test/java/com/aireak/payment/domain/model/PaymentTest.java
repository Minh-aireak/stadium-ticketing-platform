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
    void aFailureTheGatewayDidNotExplainStillCarriesAReason() {
        // Stripe sends payment_intent.payment_failed with no last_payment_error for some declines,
        // and StripeWebhookController passes null through when it is absent. A null reason used to
        // reach the customer's cancellation email as a null ${reason}, which FreeMarker refuses to
        // render — so the email was dropped, and with it the in-app notification record. The
        // customer's booking was cancelled and nothing ever told them.
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.pullDomainEvents(); // drop PaymentInitiatedEvent

        payment.markFailed(null);

        assertThat(payment.getFailureReason()).isNotBlank();
        assertThat(payment.pullDomainEvents())
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(PaymentFailedEvent.class))
                .extracting(PaymentFailedEvent::reason)
                .asString()
                .isNotBlank();
    }

    @Test
    void aBlankReasonIsTreatedTheSameAsAMissingOne() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        payment.markFailed("   ");

        assertThat(payment.getFailureReason()).isNotBlank();
    }

    @Test
    void anAmbiguousFailureWithNoReasonStaysRecognisablyAmbiguous() {
        // The prefix is how isAmbiguousFailure() lets a later webhook correct this payment to
        // SUCCEEDED, so substituting the missing reason must not cost it.
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        payment.markFailedAmbiguous(null);

        assertThat(payment.isAmbiguousFailure()).isTrue();
        assertThat(payment.getFailureReason()).startsWith(Payment.GATEWAY_AMBIGUOUS_PREFIX);
        assertThat(payment.getFailureReason()).isNotEqualTo(Payment.GATEWAY_AMBIGUOUS_PREFIX);
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

        payment.refund("req-1", AMOUNT, "gw-refund-1", "Match cancelled");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(AMOUNT);
        List<Object> events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        PaymentRefundedEvent event = (PaymentRefundedEvent) events.get(0);
        assertThat(event.gatewayRefundId()).isEqualTo("gw-refund-1");
        assertThat(event.reason()).isEqualTo("Match cancelled");
        assertThat(event.amount()).isEqualTo(AMOUNT);
        // Carried so notification-service has somewhere to send the refund email. Without it the
        // event reached a consumer that could not act on it.
        assertThat(event.customerEmail()).isEqualTo("buyer@example.com");
        assertThat(payment.newRefunds()).singleElement()
                .satisfies(refund -> assertThat(refund.refundRequestId()).isEqualTo("req-1"));
    }

    @Test
    void refundRejectsWhenNotSucceeded() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        assertThatThrownBy(() -> payment.refund("req-1", AMOUNT, "gw-refund-1", "Match cancelled"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @Test
    void refundRejectsWhenAlreadyRefunded() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markSucceeded("gw-tx-1");
        payment.refund("req-1", AMOUNT, "gw-refund-1", "first refund");

        assertThatThrownBy(() -> payment.refund("req-2", AMOUNT, "gw-refund-2", "second refund"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    /** One cancelled seat of several: the payment stays SUCCEEDED and keeps the rest refundable. */
    @Test
    void aPartialRefundKeepsThePaymentSucceededWithTheRestStillRefundable() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markSucceeded("gw-tx-1");
        payment.pullDomainEvents();
        BigDecimal part = AMOUNT.divide(BigDecimal.valueOf(4));

        payment.refund("req-1", part, "gw-refund-1", "Seat A1 cancelled");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.refundableAmount()).isEqualByComparingTo(AMOUNT.subtract(part));
        assertThat(((PaymentRefundedEvent) payment.pullDomainEvents().get(0)).amount()).isEqualByComparingTo(part);

        payment.refund("req-2", AMOUNT.subtract(part), "gw-refund-2", "Seat A2 cancelled");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.refundableAmount()).isEqualByComparingTo("0");
        assertThat(payment.newRefunds()).hasSize(2);
    }

    @Test
    void refundingMoreThanIsLeftIsRefused() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markSucceeded("gw-tx-1");
        payment.refund("req-1", AMOUNT.divide(BigDecimal.valueOf(2)), "gw-refund-1", "half");

        assertThatThrownBy(() -> payment.refund("req-2", AMOUNT, "gw-refund-2", "too much"))
                .isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo(AMOUNT.divide(BigDecimal.valueOf(2)));
    }

    // ----------------------------------------------------------------
    // Charge idempotency key
    // ----------------------------------------------------------------

    /**
     * Pinned because PaymentService#execute hard-codes the bare bookingId for a brand-new payment
     * rather than reading it off the aggregate. If these two ever disagreed, a fresh payment would
     * be charged under a key some earlier retry had already used, and Stripe would answer it with
     * that retry's stored response instead of charging anything.
     */
    @Test
    void aFreshPaymentChargesUnderTheBareBookingId() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        assertThat(payment.getChargeAttempt()).isZero();
        assertThat(payment.chargeIdempotencyKey()).isEqualTo("booking-1");
    }

    /**
     * The bug this exists for: retry used to present the same key as the attempt it was retrying,
     * and Stripe replays a stored response for 24 hours — so the retry got the original decline
     * handed back and POST /payments/{id}/retry could not succeed for a day.
     */
    @Test
    void retryAfterADefiniteDeclineChargesUnderAKeyTheGatewayHasNotSeen() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markFailed("Your card was declined");
        String keyOfTheDeclinedAttempt = payment.chargeIdempotencyKey();

        payment.retry();

        assertThat(payment.getChargeAttempt()).isEqualTo(1);
        assertThat(payment.chargeIdempotencyKey())
                .isEqualTo("booking-1:retry:1")
                .isNotEqualTo(keyOfTheDeclinedAttempt);
    }

    /**
     * And the half that must NOT change. An ambiguous failure may have charged the customer
     * already, so replaying the same key is how the lost outcome is recovered. Handing this case a
     * fresh key would turn "retry never works" into "retry can charge twice".
     */
    @Test
    void retryAfterAnAmbiguousFailureKeepsTheKeyThatMayAlreadyHaveCharged() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.markFailedAmbiguous("Read timed out");
        String keyOfTheAmbiguousAttempt = payment.chargeIdempotencyKey();

        payment.retry();

        assertThat(payment.getChargeAttempt()).isZero();
        assertThat(payment.chargeIdempotencyKey()).isEqualTo(keyOfTheAmbiguousAttempt);
    }

    /** Each definite decline moves the key on again, so a third attempt is not answered by the second. */
    @Test
    void everyDefiniteDeclineMovesTheKeyOnAgain() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        payment.markFailed("declined once");
        payment.retry();
        payment.markFailed("declined twice");
        payment.retry();

        assertThat(payment.chargeIdempotencyKey()).isEqualTo("booking-1:retry:2");
    }

    /** The counter is durable state, so a reload mid-flow must not silently reset the key. */
    @Test
    void theAttemptCounterSurvivesReconstitution() {
        Payment reloaded = Payment.reconstitute("payment-1", "booking-1", "buyer@example.com", AMOUNT, "USD",
                PaymentStatus.INITIATED, null, null, Instant.now(), 2, 5L);

        assertThat(reloaded.getChargeAttempt()).isEqualTo(2);
        assertThat(reloaded.chargeIdempotencyKey()).isEqualTo("booking-1:retry:2");
    }

    @Test
    void reconstitutePreservesVersionStatusAndRaisesNoEvents() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");

        Payment payment = Payment.reconstitute("payment-1", "booking-1", "buyer@example.com", AMOUNT, "USD",
                PaymentStatus.SUCCEEDED, "gw-tx-1", null, createdAt, 0, 3L);

        assertThat(payment.getVersion()).isEqualTo(3L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getGatewayTransactionId()).isEqualTo("gw-tx-1");
        assertThat(payment.getCreatedAt()).isEqualTo(createdAt);
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    /**
     * Zero is not null, and the gap between them is load-bearing. A row that has been inserted
     * once loads back with version 0, while version stays null only for an in-memory
     * {@code initiate()}-d payment — and Spring Data reads a null {@code @Version} as "this
     * aggregate is new". Were reconstitute() ever to default or drop a 0, every later save would
     * become an INSERT against an id that already exists (see Payment.version's javadoc).
     */
    @Test
    void reconstituteKeepsAZeroVersionDistinctFromTheNullOfANeverSavedPayment() {
        Payment loaded = Payment.reconstitute("payment-1", "booking-1", "buyer@example.com", AMOUNT, "USD",
                PaymentStatus.INITIATED, null, null, Instant.now(), 0, 0L);

        assertThat(loaded.getVersion()).isEqualTo(0L);
        assertThat(Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD").getVersion()).isNull();
    }

    @Test
    void pullDomainEventsClearsTheList() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");

        List<Object> firstPull = payment.pullDomainEvents();
        List<Object> secondPull = payment.pullDomainEvents();

        assertThat(firstPull).hasSize(1);
        assertThat(secondPull).isEmpty();
    }

    /**
     * failure_reason is a VARCHAR(500) (see {@code PaymentJpaEntity}) and nothing between the
     * gateway and the column ever measured what it was handed. The reason is whatever the gateway
     * said — since chargeFallback stopped substituting its own text for a decline, that is a real
     * third-party string on the hot path, and one long enough to overflow the column would fail
     * the whole markFailed transaction, leaving the payment INITIATED with no PaymentFailedEvent
     * and its booking stuck in PENDING_PAYMENT until the reconciliation job noticed.
     */
    @Test
    void markFailedTrimsAReasonTooLongForTheColumnItIsStoredIn() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.pullDomainEvents();

        payment.markFailed("x".repeat(4000));

        assertThat(payment.getFailureReason()).hasSizeLessThanOrEqualTo(500);
        PaymentFailedEvent event = (PaymentFailedEvent) payment.pullDomainEvents().get(0);
        assertThat(event.reason()).isEqualTo(payment.getFailureReason());
    }

    /** The ambiguous marker has to survive the trim, or a lost success reads as a plain failure. */
    @Test
    void markFailedAmbiguousKeepsItsMarkerOnAReasonTooLongForTheColumn() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.pullDomainEvents();

        payment.markFailedAmbiguous("y".repeat(4000));

        assertThat(payment.getFailureReason()).hasSizeLessThanOrEqualTo(500);
        assertThat(payment.getFailureReason()).startsWith(Payment.GATEWAY_AMBIGUOUS_PREFIX);
        assertThat(payment.isAmbiguousFailure()).isTrue();
    }

    // ---- card mode ----

    @Test
    void attachIntentMakesThePaymentCardModeWithoutMovingItOrRaisingAnEvent() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.pullDomainEvents();

        payment.attachIntent("pi_1", "pi_1_secret_x");

        assertThat(payment.isCardMode()).isTrue();
        assertThat(payment.getGatewayIntentId()).isEqualTo("pi_1");
        assertThat(payment.getClientSecret()).isEqualTo("pi_1_secret_x");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getGatewayTransactionId()).isNull();
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    void attachIntentIsOnceOnlyAndOnlyWhileInitiated() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.attachIntent("pi_1", "secret");

        assertThatThrownBy(() -> payment.attachIntent("pi_2", "secret2"))
                .isInstanceOf(InvalidPaymentStatusException.class);

        Payment failed = Payment.initiate("booking-2", null, AMOUNT, "USD");
        failed.markFailed("declined");
        assertThatThrownBy(() -> failed.attachIntent("pi_3", "secret3"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    /** A decline at the form is not a failure of the payment: nothing moves, nothing is raised. */
    @Test
    void noteAttemptFailureKeepsTheReasonAndNothingElse() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.attachIntent("pi_1", "secret");
        payment.pullDomainEvents();

        payment.noteAttemptFailure("Your card was declined.");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.getFailureReason()).isEqualTo("Your card was declined.");
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    void markSucceededClearsAReasonLeftByAnEarlierDeclinedAttempt() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.attachIntent("pi_1", "secret");
        payment.noteAttemptFailure("Your card was declined.");

        payment.markSucceeded("pi_1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getFailureReason()).isNull();
        assertThat(payment.getGatewayTransactionId()).isEqualTo("pi_1");
    }

    /** FAILED in card mode means the window closed and the seats are gone; there is nothing to re-charge. */
    @Test
    void retryIsRefusedForACardPayment() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", AMOUNT, "USD");
        payment.attachIntent("pi_1", "secret");
        payment.markFailed("Payment window of 8 minutes expired");

        assertThatThrownBy(payment::retry).isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void reconstituteWithoutIntentFieldsIsAutoMode() {
        Payment payment = Payment.reconstitute("p", "b", null, AMOUNT, "USD", PaymentStatus.INITIATED,
                null, null, java.time.Instant.now(), 0, 0L);

        assertThat(payment.isCardMode()).isFalse();
        assertThat(payment.getGatewayIntentId()).isNull();
    }
}
