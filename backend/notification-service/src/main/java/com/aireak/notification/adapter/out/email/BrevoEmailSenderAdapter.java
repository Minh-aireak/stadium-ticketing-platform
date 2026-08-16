package com.aireak.notification.adapter.out.email;

import com.aireak.notification.application.port.out.EmailSenderPort;
import com.aireak.notification.application.port.out.dto.EmailMessage;
import com.aireak.notification.config.BrevoProperties;
import com.aireak.notification.domain.exception.EmailDeliveryException;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Outbound email adapter backed by Brevo's <strong>Transactional</strong> Email API
 * ({@code POST /v3/smtp/email}). Deliberately not the Campaign/Marketing API — these are
 * one-to-one, event-triggered messages, not list broadcasts.
 *
 * <p>Uses Spring's {@link RestClient} rather than the official {@code brevo-java} SDK: that SDK
 * is built on OkHttp + Gson + Jackson 2, and Jackson 2 conflicts with Spring Boot 4.1's Jackson 3
 * autoconfiguration (the same conflict that forced JJWT out in favour of Nimbus, see
 * {@code JwtTokenGeneratorAdapter}). {@link RestClient} is already this platform's outbound HTTP
 * client everywhere else, and the endpoint is a single JSON POST.
 *
 * <p>The only {@link EmailSenderPort} implementation — there is no SMTP fallback. To keep mail out
 * of a real inbox while developing, point {@code BREVO_SENDER_EMAIL} at a Brevo test sender and
 * send to your own address; Brevo's dashboard shows every transactional message it accepted.
 */
@Slf4j
@Component
public class BrevoEmailSenderAdapter implements EmailSenderPort {

    private static final String SEND_PATH = "/v3/smtp/email";

    private final RestClient brevoRestClient;
    private final BrevoProperties properties;

    public BrevoEmailSenderAdapter(BrevoProperties properties, RestClient brevoRestClient) {
        this.properties = properties;
        this.brevoRestClient = brevoRestClient;
    }

    @Override
    public void send(EmailMessage message) {
        BrevoSendRequest request = new BrevoSendRequest(
                new BrevoContact(properties.senderEmail(), properties.senderName()),
                List.of(new BrevoContact(message.to(), message.toName())),
                message.subject(),
                message.htmlBody(),
                message.textBody()
        );

        BrevoSendResponse response;
        try {
            response = brevoRestClient.post()
                    .uri(SEND_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new EmailDeliveryException(describeFailure(res.getStatusCode(), body, message.to()));
                    })
                    .body(BrevoSendResponse.class);
        } catch (EmailDeliveryException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EmailDeliveryException(
                    "Brevo transactional email call failed for to=" + message.to() + ": " + ex.getMessage(), ex);
        }

        log.info("Brevo transactional email sent: to={}, subject='{}', messageId={}",
                message.to(), message.subject(), response == null ? "unknown" : response.messageId());
    }

    /**
     * Brevo answers a request from an IP the account hasn't allow-listed with {@code 401
     * unauthorized} and a message naming the IP — indistinguishable from a bad API key unless the
     * body is read, hence the explicit remediation hint here rather than a bare status code.
     */
    private String describeFailure(HttpStatusCode status, String body, String to) {
        String base = "Brevo rejected the transactional email for to=" + to
                + " (HTTP " + status.value() + "): " + body;
        if (isIpNotAuthorized(status, body)) {
            return base + " -- the sending machine's public IP is not on Brevo's allow-list."
                    + " Authorise it at Brevo Dashboard > Settings > Security > Authorized IPs"
                    + " (https://app.brevo.com/security/authorised_ips), then retry.";
        }
        return base;
    }

    private boolean isIpNotAuthorized(HttpStatusCode status, String body) {
        if (status.value() != 401 || body == null) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("ip address") || lower.contains("ip_address") || lower.contains("authorised ip");
    }

    // NON_NULL: Brevo's schema treats `name` as optional, but rejects an explicit null for it.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BrevoContact(String email, String name) {}

    record BrevoSendRequest(
            BrevoContact sender,
            List<BrevoContact> to,
            String subject,
            String htmlContent,
            String textContent
    ) {}

    record BrevoSendResponse(String messageId) {}
}
