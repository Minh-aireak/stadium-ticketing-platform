package com.aireak.payment.application.port.in;

import com.aireak.payment.application.port.in.command.StripeWebhookEventCommand;

/** Inbound port: reconcile a Payment against a signature-verified Stripe webhook event. */
public interface HandleStripeWebhookEventUseCase {
    void handle(StripeWebhookEventCommand command);
}
