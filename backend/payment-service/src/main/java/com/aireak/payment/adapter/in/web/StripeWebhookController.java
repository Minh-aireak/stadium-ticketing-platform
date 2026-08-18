package com.aireak.payment.adapter.in.web;

import com.aireak.payment.application.port.in.HandleStripeWebhookEventUseCase;
import com.aireak.payment.application.port.in.command.StripeWebhookEventCommand;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Inbound REST adapter: Stripe webhook delivery for {@code payment_intent.succeeded} /
 * {@code payment_intent.payment_failed} — the async reconciliation path for the synchronous
 * charge flow in {@code PaymentService} (see {@code StripeWebhookService}).
 *
 * <p>Authenticated by Stripe's own HMAC signature ({@code Stripe-Signature} header), not the
 * platform JWT — Stripe is not a logged-in user. This path is carved out of
 * {@code JwtAuthenticationFilter} via {@code jwt.excluded-paths} (application.yaml).
 *
 * <p>The raw request body (not a parsed DTO) is required: {@link Webhook#constructEvent} verifies
 * the signature against the exact bytes Stripe signed, which a JSON-parse-then-reserialize round
 * trip is not guaranteed to reproduce.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments/webhook")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final HandleStripeWebhookEventUseCase handleStripeWebhookEventUseCase;

    // Field injection, not a constructor param: keeps the Lombok @RequiredArgsConstructor used for
    // the use-case dependency simple, matching StripeGatewayAdapter's own @Value field style.
    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signatureHeader) {

        if (signatureHeader == null) {
            log.warn("Rejected Stripe webhook: missing Stripe-Signature header");
            return ResponseEntity.badRequest().build();
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException | IllegalArgumentException e) {
            log.warn("Rejected Stripe webhook: signature verification failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        extractPaymentIntent(event).ifPresentOrElse(
                paymentIntent -> handleStripeWebhookEventUseCase.handle(toCommand(event, paymentIntent)),
                () -> log.debug("Ignoring Stripe webhook event with no PaymentIntent payload: type={}", event.getType())
        );

        return ResponseEntity.ok().build();
    }

    /**
     * The event's embedded {@code api_version} won't always match this SDK's pinned version
     * (e.g. after a Stripe API upgrade on either side) — {@code getObject()} only deserializes on
     * an exact match, so a mismatch is retried with {@code deserializeUnsafe()} per Stripe's own
     * guidance, rather than silently dropping a legitimate event. Both calls are wrapped: a
     * signature-verified payload is trusted to come from Stripe, but not trusted to be a shape
     * this SDK version handles cleanly (the deserializer throws unchecked on some malformed/
     * unexpected shapes) — this endpoint returning 500 would make Stripe retry indefinitely
     * instead of the redelivery being a permanent no-op.
     */
    private Optional<PaymentIntent> extractPaymentIntent(Event event) {
        StripeObject dataObject;
        try {
            EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
            dataObject = deserializer.getObject().orElseGet(() -> {
                try {
                    return deserializer.deserializeUnsafe();
                } catch (EventDataObjectDeserializationException e) {
                    log.warn("Could not deserialize Stripe webhook event payload (unsafe fallback also failed): "
                                    + "eventId={}, type={}, error={}",
                            event.getId(), event.getType(), e.getMessage());
                    return null;
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not deserialize Stripe webhook event payload: eventId={}, type={}, error={}",
                    event.getId(), event.getType(), e.getMessage());
            return Optional.empty();
        }
        return Optional.ofNullable(dataObject)
                .filter(PaymentIntent.class::isInstance)
                .map(PaymentIntent.class::cast);
    }

    private StripeWebhookEventCommand toCommand(Event event, PaymentIntent paymentIntent) {
        String bookingId = paymentIntent.getMetadata() != null
                ? paymentIntent.getMetadata().get("bookingId")
                : null;
        String failureMessage = paymentIntent.getLastPaymentError() != null
                ? paymentIntent.getLastPaymentError().getMessage()
                : null;
        return new StripeWebhookEventCommand(
                event.getId(), event.getType(), bookingId, paymentIntent.getId(), failureMessage);
    }
}
