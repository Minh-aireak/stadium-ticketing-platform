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
import java.util.HashMap;
import java.util.List;
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

    /** What notifications.body was before V4 widened it to TEXT. */
    private static final int PRE_V4_BODY_COLUMN_WIDTH = 2000;

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

    /**
     * The expected timestamp is written out in full rather than recomputed from the service's own
     * formatting expression. It used to be recomputed, {@code ZoneId.systemDefault()} and all,
     * which made it agree with the code by construction on every host: the receipt read 16:30 on
     * a developer's machine and 09:30 in the container, and this test was green for both.
     *
     * <p>A literal fails for either of the two things that can go wrong. On a UTC host the hour
     * is wrong; on a +07:00 host the offset suffix is missing. There is no assertion that can
     * prove from inside one JVM that the zone is not the default one, since {@code PAID_AT_FORMAT}
     * captures it once at class-init -- what this pins is the only thing that matters to the
     * customer, which is that the same instant renders as the same string everywhere and says
     * which clock it is on.
     */
    @Test
    void sendPaymentSuccessEmail_carriesCustomerAmountOrderIdAndPaymentTimeInBothParts() {
        Instant paidAt = Instant.parse("2026-08-16T09:30:00Z");

        service.sendPaymentSuccessEmail(new PaymentSuccessEmail(
                "john@example.com", "booking-42", new BigDecimal("1250000"), "VND", paidAt));

        EmailMessage sent = captureSent();
        assertThat(sent.subject()).contains("booking-42");
        for (String body : new String[]{sent.htmlBody(), sent.textBody()}) {
            assertThat(body)
                    .contains("john")
                    .contains("booking-42")
                    .contains("1,250,000 VND")
                    .contains("16/08/2026 16:30:00 UTC+07:00");
        }
        assertThat(sent.textBody()).doesNotContain("<html");
    }

    /** Same formatter, second call site — see {@link #sendPaymentSuccessEmail_carriesCustomerAmountOrderIdAndPaymentTimeInBothParts}. */
    @Test
    void sendRefundIssuedEmail_carriesTheOrderAmountReasonAndRefundTimeInBothParts() {
        service.sendRefundIssuedEmail(new TransactionalEmailService.RefundIssuedEmail(
                "john@example.com", "booking-9", new BigDecimal("249.50"), "USD",
                "Match cancelled", Instant.parse("2026-08-25T10:15:30Z")));

        EmailMessage sent = captureSent();
        assertThat(sent.subject()).contains("booking-9");
        for (String body : new String[]{sent.htmlBody(), sent.textBody()}) {
            assertThat(body).contains("booking-9");
            assertThat(body).contains("249.50 USD");
            assertThat(body).contains("Match cancelled");
            assertThat(body).contains("25/08/2026 17:15:30 UTC+07:00");
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

    /**
     * notifications.body is TEXT rather than a bounded VARCHAR, and V4 justifies that with a seat
     * list nothing capped. Both services cap it now -- Booking.MAX_TICKETS and
     * SeatRequestLimits.MAX_SEATS_PER_REQUEST are both 8 -- so a confirmation body no longer grows
     * without limit. A cancellation body still does: reason arrives from an admin's free-text
     * CancelMatchRequest and is bounded at no hop between there and here. These two pin which of
     * the two templates is now the reason the ceiling must stay off, so that a future change to
     * either cap, or to the templates, has to come back through this file.
     */
    @Test
    void aCancellationReasonIsWhatStillPushesARenderedBodyPastTheOldCeiling() {
        Map<String, Object> model = new HashMap<>();
        model.put("bookingId", "booking-1");
        model.put("showtimeId", "showtime-1");
        model.put("reason", "x".repeat(5_000));

        Optional<EmailContent> content = service.sendTemplatedEmail(
                "booking-cancelled", "Your booking has been cancelled", "john@example.com", model);

        assertThat(content).isPresent();
        assertThat(content.get().textBody()).hasSizeGreaterThan(PRE_V4_BODY_COLUMN_WIDTH);
    }

    @Test
    void aConfirmationCarryingEverySeatABookingMayHoldFitsWellInsideTheOldCeiling() {
        Map<String, Object> model = new HashMap<>();
        model.put("bookingId", "booking-1");
        model.put("showtimeId", "showtime-1");
        // Booking.MAX_TICKETS seats, the most one booking can ever carry into this template.
        model.put("seatCodes", List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8"));
        model.put("amount", new BigDecimal("1250000"));
        model.put("currency", "VND");

        Optional<EmailContent> content = service.sendTemplatedEmail(
                "booking-confirmed", "Your booking is confirmed!", "john@example.com", model);

        assertThat(content).isPresent();
        assertThat(content.get().textBody()).hasSizeLessThan(PRE_V4_BODY_COLUMN_WIDTH);
    }

    /**
     * Why {@code NotificationDispatchService} no longer puts {@code customerId} or
     * {@code occurredAt} into the models for these three templates: not one of them renders
     * either. Five model entries across three messages went into FreeMarker and came back out
     * again untouched.
     *
     * <p>Green both before that removal and after it, and it has to be -- a model entry no
     * template reads has no observable effect, which is exactly why nothing caught it. This is the
     * executable form of the grep that justified removing them, kept so the claim is rechecked on
     * every run rather than on the day somebody thinks to grep again.
     *
     * <p>The values supplied here are ones a real model would no longer carry, on purpose. If a
     * template ever starts rendering one, this fails, and the put has to come back with it.
     */
    @Test
    void noTemplateRendersTheCustomerIdOrOccurredAtThatUsedToBePutIntoItsModel() {
        String customerId = "cust-should-not-appear";
        String occurredAt = "2026-08-31T10:00:00Z";

        Map<String, Object> confirmed = new HashMap<>();
        confirmed.put("bookingId", "booking-1");
        confirmed.put("showtimeId", "showtime-1");
        confirmed.put("seatCodes", List.of("A1"));
        confirmed.put("amount", new BigDecimal("1250000"));
        confirmed.put("currency", "VND");
        confirmed.put("customerId", customerId);
        confirmed.put("occurredAt", occurredAt);

        Map<String, Object> cancelled = new HashMap<>();
        cancelled.put("bookingId", "booking-1");
        cancelled.put("showtimeId", "showtime-1");
        cancelled.put("reason", "Match cancelled");
        cancelled.put("customerId", customerId);
        cancelled.put("occurredAt", occurredAt);

        Map<String, Object> activated = new HashMap<>();
        activated.put("accountId", "acc-1");
        activated.put("email", "john@example.com");
        activated.put("occurredAt", occurredAt);

        assertRendersWithout("booking-confirmed", confirmed, customerId, occurredAt);
        assertRendersWithout("booking-cancelled", cancelled, customerId, occurredAt);
        assertRendersWithout("account-activated", activated, customerId, occurredAt);
    }

    private void assertRendersWithout(String templateBase, Map<String, Object> model, String... absent) {
        Optional<EmailContent> content = service.sendTemplatedEmail(
                templateBase, "Subject", "john@example.com", model);

        assertThat(content).as("%s must still render", templateBase).isPresent();
        for (String body : new String[]{content.get().htmlBody(), content.get().textBody()}) {
            assertThat(body).doesNotContain(absent);
        }
    }

    private EmailMessage captureSent() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSenderPort).send(captor.capture());
        return captor.getValue();
    }
}
