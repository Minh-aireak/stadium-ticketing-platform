package com.aireak.payment.application.service;

import com.aireak.payment.application.port.in.HandleStripeWebhookEventUseCase;
import com.aireak.payment.application.port.in.command.StripeWebhookEventCommand;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.application.port.out.ProcessedWebhookEventRepository;
import com.aireak.payment.domain.exception.InvalidPaymentStatusException;
import com.aireak.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reconciles Payment state against signature-verified Stripe webhook events — the async
 * backstop for the synchronous charge flow in {@link PaymentService} (e.g. this process
 * crashing between {@code PaymentGatewayPort#charge} returning and the outcome being persisted).
 *
 * <p><strong>Idempotency</strong>: guarded twice over. First by {@link ProcessedWebhookEventRepository}
 * keyed on the Stripe event id (Stripe redelivers until it sees 2xx). Second, as a backstop, by
 * {@link Payment}'s own INITIATED-only state guard — {@link InvalidPaymentStatusException} from a
 * reconciliation that has already happened via the synchronous path (or a prior webhook) is treated
 * as a no-op rather than an error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService implements HandleStripeWebhookEventUseCase {

    private static final String EVENT_PAYMENT_SUCCEEDED = "payment_intent.succeeded";
    private static final String EVENT_PAYMENT_FAILED = "payment_intent.payment_failed";

    private final ProcessedWebhookEventRepository processedWebhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSagaSteps sagaSteps;

    @Override
    public void handle(StripeWebhookEventCommand command) {
        if (processedWebhookEventRepository.existsByEventId(command.eventId())) {
            log.info("Stripe webhook event already processed, skipping: eventId={}", command.eventId());
            return;
        }

        switch (command.eventType()) {
            case EVENT_PAYMENT_SUCCEEDED -> reconcile(command, Reconciliation.SUCCEEDED);
            case EVENT_PAYMENT_FAILED -> reconcile(command, Reconciliation.FAILED);
            default -> log.debug("Ignoring unhandled Stripe webhook event type: {}", command.eventType());
        }

        processedWebhookEventRepository.markProcessed(command.eventId(), command.eventType());
    }

    private void reconcile(StripeWebhookEventCommand command, Reconciliation outcome) {
        Optional<Payment> payment = findPayment(command.bookingId());
        if (payment.isEmpty()) {
            return;
        }
        String paymentId = payment.get().getPaymentId();
        boolean wasAmbiguousFailure = payment.get().isAmbiguousFailure();
        try {
            switch (outcome) {
                case SUCCEEDED -> sagaSteps.markSucceeded(paymentId, command.paymentIntentId());
                case FAILED -> sagaSteps.markFailed(paymentId, command.failureMessage());
            }
            if (wasAmbiguousFailure && outcome == Reconciliation.SUCCEEDED) {
                log.info("CORRECTION: Overriding payment status from FAILED (gateway ambiguous) to SUCCEEDED via Stripe webhook: paymentId={}, bookingId={}, gatewayTxId={}",
                        paymentId, command.bookingId(), command.paymentIntentId());
            } else {
                log.info("Payment reconciled via Stripe webhook: paymentId={}, bookingId={}, outcome={}",
                        paymentId, command.bookingId(), outcome);
            }
        } catch (InvalidPaymentStatusException e) {
            log.info("Payment already in a terminal state, Stripe webhook is a no-op: paymentId={}, outcome={}",
                    paymentId, outcome);
        }
    }

    private Optional<Payment> findPayment(String bookingId) {
        if (bookingId == null) {
            log.warn("Stripe webhook event has no bookingId metadata, cannot reconcile");
            return Optional.empty();
        }
        Optional<Payment> payment = paymentRepository.findByBookingId(bookingId);
        if (payment.isEmpty()) {
            log.warn("Stripe webhook event references unknown bookingId={}", bookingId);
        }
        return payment;
    }

    private enum Reconciliation { SUCCEEDED, FAILED }
}
