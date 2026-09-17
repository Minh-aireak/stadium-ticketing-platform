package com.aireak.payment.application.port.in;

import com.aireak.payment.domain.model.Payment;

import java.util.Optional;

/**
 * Inbound port, card mode: ask the gateway what became of the intent the customer just confirmed
 * in the browser, and record it. Called by the storefront right after Stripe.js reports the
 * confirmation, so the outcome lands without waiting for a webhook that, on a developer machine
 * with no {@code stripe listen} running, never comes. The browser's own report of success is not
 * trusted: the gateway is asked.
 */
public interface SyncPaymentUseCase {
    /** @return the payment as it stands after the sync, or empty if no payment exists for the booking */
    Optional<Payment> syncWithGateway(String bookingId);
}
