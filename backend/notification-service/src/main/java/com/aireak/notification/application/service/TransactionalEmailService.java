package com.aireak.notification.application.service;

import com.aireak.notification.application.port.out.EmailSenderPort;
import com.aireak.notification.application.port.out.dto.EmailMessage;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The transactional-email module: renders each message from a paired HTML + plain-text FreeMarker
 * template and hands it to whichever {@link EmailSenderPort} is configured (Brevo by default).
 *
 * <p>{@link #sendEmail} is the shared primitive every specific message goes through. It never
 * throws — a provider outage, a bad API key, or an IP that isn't on Brevo's allow-list must not
 * take down the Kafka consumer thread or the upstream business flow that produced the event. Every
 * failure is logged at ERROR with the provider's own response text.
 *
 * <p>The three {@code send*Email} methods return the rendered {@link EmailContent} so the caller
 * can reuse it for the in-app notification record without a second template pass. An empty result
 * means the templates could not be rendered, so nothing was sent; a <em>present</em> result means
 * the message was rendered and handed to the provider — check the logs for delivery failures.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionalEmailService {

    private static final String WELCOME_TEMPLATE = "account-welcome";
    private static final String PAYMENT_SUCCESS_TEMPLATE = "payment-success";
    private static final String PASSWORD_RESET_TEMPLATE = "password-reset";

    private static final DateTimeFormatter PAID_AT_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final EmailSenderPort emailSenderPort;
    private final Configuration freemarkerConfig;

    /**
     * Shared send primitive. Returns whether the provider accepted the message; never propagates
     * a delivery failure to the caller.
     */
    public boolean sendEmail(EmailMessage message) {
        try {
            emailSenderPort.send(message);
            return true;
        } catch (Exception ex) {
            log.error("Transactional email delivery failed (business flow continues): to={}, subject='{}': {}",
                    message.to(), message.subject(), ex.getMessage(), ex);
            return false;
        }
    }

    /** Sent once registration succeeds; carries the one-time email-verification link. */
    public Optional<EmailContent> sendWelcomeEmail(WelcomeEmail data) {
        Map<String, Object> model = new HashMap<>();
        model.put("customerName", displayName(data.to()));
        model.put("email", data.to());
        model.put("accountId", data.accountId());
        model.put("verificationUrl", data.verificationUrl());
        return sendTemplatedEmail(WELCOME_TEMPLATE, "Welcome to Stadium Ticketing — verify your email",
                data.to(), model);
    }

    /** Sent once a payment is confirmed; carries customer, amount, order id and payment time. */
    public Optional<EmailContent> sendPaymentSuccessEmail(PaymentSuccessEmail data) {
        Map<String, Object> model = new HashMap<>();
        model.put("customerName", displayName(data.to()));
        model.put("orderId", data.orderId());
        model.put("amount", formatAmount(data.amount(), data.currency()));
        model.put("currency", data.currency());
        model.put("paidAt", PAID_AT_FORMAT.format(data.paidAt()));
        return sendTemplatedEmail(PAYMENT_SUCCESS_TEMPLATE, "Payment received — order " + data.orderId(),
                data.to(), model);
    }

    /** Sent on a forgot-password request; carries the reset link and how long it stays valid. */
    public Optional<EmailContent> sendPasswordResetEmail(PasswordResetEmail data) {
        Map<String, Object> model = new HashMap<>();
        model.put("customerName", displayName(data.to()));
        model.put("resetUrl", data.resetUrl());
        model.put("expiresInMinutes", data.expiresInMinutes());
        return sendTemplatedEmail(PASSWORD_RESET_TEMPLATE, "Reset your Stadium Ticketing password",
                data.to(), model);
    }

    /**
     * Renders {@code <templateBase>.html.ftl} + {@code <templateBase>.txt.ftl} against
     * {@code model} and sends the result — the shared body behind the three specific methods
     * above, and the entry point for every other event-driven email (see
     * {@link NotificationDispatchService}).
     */
    public Optional<EmailContent> sendTemplatedEmail(String templateBase, String subject, String to,
                                                     Map<String, Object> model) {
        String html;
        String text;
        try {
            html = render(templateBase + ".html.ftl", model);
            text = render(templateBase + ".txt.ftl", model);
        } catch (Exception ex) {
            log.error("Failed to render email templates '{}' for to={} — nothing sent: {}",
                    templateBase, to, ex.getMessage(), ex);
            return Optional.empty();
        }

        sendEmail(new EmailMessage(to, displayName(to), subject, html, text));
        return Optional.of(new EmailContent(subject, html, text));
    }

    private String render(String templateName, Map<String, Object> model) throws Exception {
        Template template = freemarkerConfig.getTemplate(templateName);
        StringWriter writer = new StringWriter();
        template.process(model, writer);
        return writer.toString();
    }

    /**
     * No account in this platform ever captures a display name (registration is email + password
     * only), so the address's local part is the closest thing to one — "jane.doe@example.com"
     * greets as "jane.doe".
     */
    private String displayName(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private String formatAmount(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
        format.setMaximumFractionDigits(2);
        return format.format(amount) + " " + currency;
    }

    public record EmailContent(String subject, String htmlBody, String textBody) {}

    public record WelcomeEmail(String to, String accountId, String verificationUrl) {}

    public record PaymentSuccessEmail(String to, String orderId, BigDecimal amount, String currency,
                                      Instant paidAt) {}

    public record PasswordResetEmail(String to, String resetUrl, long expiresInMinutes) {}
}
