package com.aireak.notification.application.service;

import com.aireak.notification.application.port.out.EmailSenderPort;
import com.aireak.notification.application.port.out.dto.EmailMessage;
import com.aireak.notification.application.service.TransactionalEmailService.EmailContent;
import com.aireak.notification.application.service.TransactionalEmailService.PasswordResetEmail;
import com.aireak.notification.application.service.TransactionalEmailService.PaymentSuccessEmail;
import com.aireak.notification.application.service.TransactionalEmailService.WelcomeEmail;
import com.aireak.notification.domain.exception.EmailDeliveryException;
import freemarker.template.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Renders against the real production templates (loaded off the classpath), so a template that
 * references a variable the service doesn't put in the model fails here rather than in production.
 */
@ExtendWith(MockitoExtension.class)
class TransactionalEmailServiceTest {

    @Mock
    private EmailSenderPort emailSenderPort;

    private TransactionalEmailService service;

    @BeforeEach
    void setUp() {
        Configuration freemarkerConfig = new Configuration(Configuration.VERSION_2_3_32);
        freemarkerConfig.setClassForTemplateLoading(TransactionalEmailServiceTest.class, "/templates");
        freemarkerConfig.setDefaultEncoding("UTF-8");
        service = new TransactionalEmailService(emailSenderPort, freemarkerConfig);
    }

    @Test
    void sendWelcomeEmail_sendsHtmlAndTextBothCarryingTheVerificationLink() {
        Optional<EmailContent> content = service.sendWelcomeEmail(
                new WelcomeEmail("jane.doe@example.com", "acc-1",
                        "http://localhost:8081/api/v1/auth/verify-email?token=tok-123"));

        EmailMessage sent = captureSent();
        assertThat(sent.to()).isEqualTo("jane.doe@example.com");
        assertThat(sent.toName()).isEqualTo("jane.doe");
        assertThat(sent.subject()).contains("Welcome");
        assertThat(sent.htmlBody())
                .contains("<html")
                .contains("jane.doe")
                .contains("acc-1")
                .contains("verify-email?token=tok-123");
        assertThat(sent.textBody())
                .doesNotContain("<html")
                .contains("jane.doe")
                .contains("verify-email?token=tok-123");

        assertThat(content).isPresent();
        assertThat(content.get().textBody()).isEqualTo(sent.textBody());
    }

    @Test
    void sendPaymentSuccessEmail_carriesCustomerAmountOrderIdAndPaymentTimeInBothParts() {
        Instant paidAt = Instant.parse("2026-08-16T09:30:00Z");
        String expectedPaidAt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(paidAt);

        service.sendPaymentSuccessEmail(new PaymentSuccessEmail(
                "john@example.com", "booking-42", new BigDecimal("1250000"), "VND", paidAt));

        EmailMessage sent = captureSent();
        assertThat(sent.subject()).contains("booking-42");
        for (String body : new String[]{sent.htmlBody(), sent.textBody()}) {
            assertThat(body)
                    .contains("john")
                    .contains("booking-42")
                    .contains("1,250,000 VND")
                    .contains(expectedPaidAt);
        }
        assertThat(sent.textBody()).doesNotContain("<html");
    }

    @Test
    void sendRefundIssuedEmail_carriesTheOrderAmountAndReasonInBothParts() {
        service.sendRefundIssuedEmail(new TransactionalEmailService.RefundIssuedEmail(
                "john@example.com", "booking-9", new BigDecimal("249.50"), "USD",
                "Match cancelled", Instant.parse("2026-08-25T10:15:30Z")));

        EmailMessage sent = captureSent();
        assertThat(sent.subject()).contains("booking-9");
        for (String body : new String[]{sent.htmlBody(), sent.textBody()}) {
            assertThat(body).contains("booking-9");
            assertThat(body).contains("249.50 USD");
            assertThat(body).contains("Match cancelled");
        }
    }

    @Test
    void sendRefundIssuedEmail_stillSendsWhenNoReasonWasRecorded() {
        // reason is the one optional field in this model, defaulted in the template. A refund the
        // customer is not told about is worse than a refund with a generic reason line.
        service.sendRefundIssuedEmail(new TransactionalEmailService.RefundIssuedEmail(
                "john@example.com", "booking-9", new BigDecimal("249.50"), "USD",
                null, Instant.parse("2026-08-25T10:15:30Z")));

        assertThat(captureSent().textBody()).contains("booking-9");
    }

    @Test
    void sendPasswordResetEmail_carriesTheResetLinkAndItsExpiryWindow() {
        service.sendPasswordResetEmail(new PasswordResetEmail(
                "john@example.com", "http://localhost:5173/reset-password?token=reset-tok", 30));

        EmailMessage sent = captureSent();
        assertThat(sent.subject()).contains("Reset");
        for (String body : new String[]{sent.htmlBody(), sent.textBody()}) {
            assertThat(body)
                    .contains("http://localhost:5173/reset-password?token=reset-tok")
                    .contains("30");
        }
        assertThat(sent.textBody()).doesNotContain("<html");
    }

    @Test
    void sendEmail_whenDeliveryFails_swallowsSoTheBusinessFlowKeepsRunning() {
        doThrow(new EmailDeliveryException("Brevo rejected: IP not authorized"))
                .when(emailSenderPort).send(org.mockito.ArgumentMatchers.any());

        boolean accepted = service.sendEmail(
                new EmailMessage("john@example.com", "john", "Subject", "<p>x</p>", "x"));

        assertThat(accepted).isFalse();
    }

    @Test
    void sendPasswordResetEmail_whenDeliveryFails_stillReturnsRenderedContentAndDoesNotThrow() {
        doThrow(new EmailDeliveryException("Brevo unreachable"))
                .when(emailSenderPort).send(org.mockito.ArgumentMatchers.any());

        Optional<EmailContent> content = service.sendPasswordResetEmail(
                new PasswordResetEmail("john@example.com", "http://localhost:5173/reset-password?token=t", 30));

        assertThat(content).isPresent();
    }

    @Test
    void sendTemplatedEmail_stillSendsTheCancellationWhenNoReasonWasSupplied() {
        // FreeMarker refuses to render a null ${...}, and this service catches that and sends
        // nothing — which also skips the in-app notification record, since NotificationDispatchService
        // builds it from the rendered text. The reason is the one genuinely optional field in this
        // model, so it carries a default in the template: losing one line beats never telling a
        // customer their booking was cancelled. Every other variable stays undefaulted on purpose,
        // where a missing value means a real bug that should fail loudly.
        Map<String, Object> model = new HashMap<>();
        model.put("bookingId", "booking-1");
        model.put("customerId", "cust-1");
        model.put("showtimeId", "showtime-1");
        model.put("reason", null);

        Optional<EmailContent> content = service.sendTemplatedEmail(
                "booking-cancelled", "Your booking has been cancelled", "john@example.com", model);

        assertThat(content).isPresent();
        assertThat(content.get().textBody()).contains("booking-1");
    }

    @Test
    void sendTemplatedEmail_whenTemplateIsMissing_sendsNothingAndReportsEmpty() {
        Optional<EmailContent> content = service.sendTemplatedEmail(
                "no-such-template", "Subject", "john@example.com", Map.of());

        assertThat(content).isEmpty();
        verify(emailSenderPort, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void displayName_fallsBackToTheWholeAddressWhenThereIsNoLocalPart() {
        service.sendPasswordResetEmail(new PasswordResetEmail("@example.com", "http://x/reset", 15));

        assertThat(captureSent().toName()).isEqualTo("@example.com");
    }

    private EmailMessage captureSent() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSenderPort).send(captor.capture());
        return captor.getValue();
    }
}
