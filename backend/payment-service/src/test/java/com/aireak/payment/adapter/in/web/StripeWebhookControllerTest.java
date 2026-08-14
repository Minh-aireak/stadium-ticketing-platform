package com.aireak.payment.adapter.in.web;

import com.aireak.payment.application.port.in.HandleStripeWebhookEventUseCase;
import com.stripe.Stripe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms signature verification and that this endpoint is reachable without a JWT (Stripe
 * signs requests itself — see jwt.excluded-paths in application.yaml, mirrored here via
 * TestPropertySource so the slice test doesn't need a bearer token).
 */
@WebMvcTest(StripeWebhookController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret=",
        "jwt.excluded-paths[0]=POST:/api/v1/payments/webhook",
        "stripe.webhook-secret=whsec_test_secret_at_least_32_bytes"
})
class StripeWebhookControllerTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret_at_least_32_bytes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HandleStripeWebhookEventUseCase handleStripeWebhookEventUseCase;

    @Test
    void rejectsRequestWithNoSignatureHeader() throws Exception {
        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentIntentSucceededPayload("evt-1", "pi-1", "booking-1")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(handleStripeWebhookEventUseCase);
    }

    @Test
    void rejectsRequestWithInvalidSignature() throws Exception {
        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1700000000,v1=deadbeef")
                        .content(paymentIntentSucceededPayload("evt-1", "pi-1", "booking-1")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(handleStripeWebhookEventUseCase);
    }

    @Test
    void acceptsValidlySignedPaymentIntentSucceededAndDelegatesToUseCase() throws Exception {
        String payload = paymentIntentSucceededPayload("evt-1", "pi-1", "booking-1");

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk());

        verify(handleStripeWebhookEventUseCase).handle(argThat(command ->
                command.eventId().equals("evt-1")
                        && command.eventType().equals("payment_intent.succeeded")
                        && command.bookingId().equals("booking-1")
                        && command.paymentIntentId().equals("pi-1")
                        && command.failureMessage() == null));
    }

    @Test
    void acceptsValidlySignedPaymentIntentFailedAndExtractsFailureMessage() throws Exception {
        String payload = paymentIntentFailedPayload("evt-2", "pi-2", "booking-2", "Your card was declined.");

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk());

        verify(handleStripeWebhookEventUseCase).handle(argThat(command ->
                command.eventId().equals("evt-2")
                        && command.eventType().equals("payment_intent.payment_failed")
                        && command.bookingId().equals("booking-2")
                        && command.paymentIntentId().equals("pi-2")
                        && "Your card was declined.".equals(command.failureMessage())));
    }

    private String sign(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hmacBytes = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hmacBytes) {
            hex.append(String.format("%02x", b));
        }
        return "t=" + timestamp + ",v1=" + hex;
    }

    private String paymentIntentSucceededPayload(String eventId, String paymentIntentId, String bookingId) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "type": "payment_intent.succeeded",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "payment_intent",
                      "status": "succeeded",
                      "metadata": { "bookingId": "%s" }
                    }
                  }
                }
                """.formatted(eventId, Stripe.API_VERSION, paymentIntentId, bookingId);
    }

    private String paymentIntentFailedPayload(String eventId, String paymentIntentId, String bookingId, String failureMessage) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "type": "payment_intent.payment_failed",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "payment_intent",
                      "status": "requires_payment_method",
                      "metadata": { "bookingId": "%s" },
                      "last_payment_error": { "message": "%s" }
                    }
                  }
                }
                """.formatted(eventId, Stripe.API_VERSION, paymentIntentId, bookingId, failureMessage);
    }
}
