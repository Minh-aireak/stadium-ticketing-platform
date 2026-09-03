package com.aireak.notification.application.service;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.event.PasswordResetRequestedEvent;
import com.aireak.notification.application.port.out.EmailSenderPort;
import com.aireak.notification.application.port.out.NotificationRepository;
import com.aireak.notification.application.port.out.ProcessedEventRepository;
import com.aireak.notification.application.port.out.dto.EmailMessage;
import com.aireak.notification.config.AppLinkProperties;
import com.aireak.notification.config.FreeMarkerEscapingConfig;
import com.aireak.notification.domain.model.Notification;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import freemarker.template.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.aireak.common.kafka.KafkaTopics.ACCOUNT_ACTIVATED;
import static com.aireak.common.kafka.KafkaTopics.ACCOUNT_REGISTERED;
import static com.aireak.common.kafka.KafkaTopics.BOOKING_CANCELLED;
import static com.aireak.common.kafka.KafkaTopics.BOOKING_CONFIRMED;
import static com.aireak.common.kafka.KafkaTopics.PASSWORD_RESET_REQUESTED;
import static com.aireak.common.kafka.KafkaTopics.PAYMENT_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the notification-service Kafka consumer flow at the unit level using the same
 * {@link EventEnvelope} shape producers actually build: the outbox publishers call
 * {@code EventEnvelope.of(topic, domainEvent, traceId)}, so {@code eventType} is the
 * {@code KafkaTopics} routing string this service switches on — not a Java class name. The
 * consumers hand that value straight to {@link NotificationDispatchService#send}.
 *
 * <p>Wired against the real {@link TransactionalEmailService} and the real templates, so a
 * routing change that leaves a template variable unbound fails here.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock
    private EmailSenderPort emailSenderPort;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private NotificationRepository notificationRepository;

    private NotificationDispatchService service;

    @BeforeEach
    void setUp() {
        Configuration freemarkerConfig = new Configuration(Configuration.VERSION_2_3_32);
        freemarkerConfig.setClassForTemplateLoading(NotificationDispatchServiceTest.class, "/templates");
        freemarkerConfig.setDefaultEncoding("UTF-8");
        // Same call production makes, so escaping behaviour here is production's behaviour.
        FreeMarkerEscapingConfig.applyTo(freemarkerConfig);

        // The real NotificationRecordingSteps over mocked repositories: it is the only writer, so
        // the existing assertions on notificationRepository/processedEventRepository still observe
        // exactly what production would persist — just from behind the transactional boundary that
        // now keeps the email send out of a database transaction.
        service = new NotificationDispatchService(
                new TransactionalEmailService(emailSenderPort, freemarkerConfig),
                new NotificationRecordingSteps(notificationRepository, processedEventRepository),
                new AppLinkProperties("http://localhost:8081", "http://localhost:5173"));
    }

    @Test
    void send_confirmedBookingAsProducedInProduction_rendersRealTemplateWithAmountAndCurrency() {
        BookingConfirmedEvent bookingConfirmed = new BookingConfirmedEvent(
                "booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                List.of("A1", "A2"),
                new BookingConfirmedEvent.BookingAmount(new BigDecimal("100.00"), "VND"),
                Instant.now());

        dispatch(BOOKING_CONFIRMED, bookingConfirmed);

        EmailMessage sent = captureSent();
        assertThat(sent.to()).isEqualTo("customer-1@example.com");
        assertThat(sent.htmlBody()).contains("100").contains("VND");
        assertThat(sent.textBody()).contains("100").contains("VND").doesNotContain("<html");

        Notification notification = captureSavedNotification();
        assertThat(notification.getRecipientId()).isEqualTo("customer-1");
        assertThat(notification.isRead()).isFalse();
    }

    @Test
    void send_cancelledBookingAsProducedInProduction_rendersRealTemplate() {
        BookingCancelledEvent cancelled = new BookingCancelledEvent(
                "booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                "customer requested refund", Instant.now());

        dispatch(BOOKING_CANCELLED, cancelled);

        EmailMessage sent = captureSent();
        assertThat(sent.to()).isEqualTo("customer-1@example.com");
        assertThat(sent.htmlBody()).contains("customer requested refund");
        assertThat(sent.textBody()).contains("customer requested refund");
    }

    /**
     * The cancellation reason is not always server-authored: for a cancelled match it is free text
     * an administrator typed, and it reaches the inbox of every customer holding a booking for that
     * match. FreeMarker escapes nothing unless a template's output format says to, and these
     * templates are named {@code *.html.ftl} rather than {@code *.ftlh}, so nothing inferred it —
     * {@code FreeMarkerEscapingConfig} is what makes this pass.
     */
    @Test
    void send_cancelledBooking_escapesMarkupInTheReasonInTheHtmlBodyButNotThePlainTextOne() {
        String injected = "<img src=x onerror=alert(1)>";
        BookingCancelledEvent cancelled = new BookingCancelledEvent(
                "booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                injected, Instant.now());

        dispatch(BOOKING_CANCELLED, cancelled);

        EmailMessage sent = captureSent();
        assertThat(sent.htmlBody()).doesNotContain(injected).contains("&lt;img");
        // The plain-text body must stay literal — escaping there would show readers "&amp;".
        assertThat(sent.textBody()).contains(injected);
    }

    @Test
    void send_cancelledBooking_leavesAnAmpersandAloneInThePlainTextBody() {
        BookingCancelledEvent cancelled = new BookingCancelledEvent(
                "booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                "Barcelona & Madrid postponed", Instant.now());

        dispatch(BOOKING_CANCELLED, cancelled);

        assertThat(captureSent().textBody()).contains("Barcelona & Madrid postponed");
    }

    @Test
    void send_accountRegistered_sendsTheWelcomeEmailWithTheVerificationLink() {
        AccountRegisteredEvent registered = new AccountRegisteredEvent(
                new AccountRegisteredEvent.AccountId("acc-1"),
                new AccountRegisteredEvent.Email("new-user@example.com"),
                "test-verification-token",
                Instant.now());

        dispatch(ACCOUNT_REGISTERED, registered);

        EmailMessage sent = captureSent();
        assertThat(sent.to()).isEqualTo("new-user@example.com");
        assertThat(sent.htmlBody())
                .contains("http://localhost:8081/api/v1/auth/verify-email?token=test-verification-token");
        assertThat(sent.textBody())
                .contains("http://localhost:8081/api/v1/auth/verify-email?token=test-verification-token");

        assertThat(captureSavedNotification().getRecipientId()).isEqualTo("acc-1");
    }

    @Test
    void send_accountActivated_rendersRealTemplateAndEmailsTheAccountAddress() {
        AccountActivatedEvent activated = new AccountActivatedEvent(
                new AccountActivatedEvent.AccountId("acc-1"),
                new AccountActivatedEvent.Email("new-user@example.com"),
                Instant.now());

        dispatch(ACCOUNT_ACTIVATED, activated);

        EmailMessage sent = captureSent();
        assertThat(sent.to()).isEqualTo("new-user@example.com");
        assertThat(sent.htmlBody()).contains("acc-1");
        assertThat(captureSavedNotification().getRecipientId()).isEqualTo("acc-1");
    }

    @Test
    void send_paymentSucceeded_emailsTheReceiptToTheAddressCapturedAtInitiation() {
        Instant paidAt = Instant.parse("2026-08-16T09:30:00Z");
        PaymentSucceededEvent succeeded = new PaymentSucceededEvent(
                "pay-1", "booking-9", "buyer@example.com",
                new BigDecimal("450000"), "VND", "pi_test_123", paidAt);

        dispatch(PAYMENT_SUCCEEDED, succeeded);

        EmailMessage sent = captureSent();
        assertThat(sent.to()).isEqualTo("buyer@example.com");
        assertThat(sent.subject()).contains("booking-9");
        assertThat(sent.htmlBody()).contains("buyer").contains("booking-9").contains("450,000 VND");
        assertThat(sent.textBody()).contains("450,000 VND").doesNotContain("<html");
    }

    @Test
    void send_paymentSucceededWithoutCustomerEmail_isMarkedProcessedWithoutSendingAnything() {
        PaymentSucceededEvent succeeded = new PaymentSucceededEvent(
                "pay-1", "booking-9", null,
                new BigDecimal("450000"), "VND", "pi_test_123", Instant.now());

        EventEnvelope<PaymentSucceededEvent> envelope = EventEnvelope.of(PAYMENT_SUCCEEDED, succeeded, null);
        when(processedEventRepository.existsByEventId(envelope.getEventId())).thenReturn(false);
        service.send(envelope.getEventId(), envelope.getEventType(), envelope.getPayload());

        verifyNoInteractions(emailSenderPort);
        verify(processedEventRepository).markProcessed(envelope.getEventId(), PAYMENT_SUCCEEDED);
    }

    @Test
    void send_passwordResetRequested_emailsTheFrontendResetLinkAndItsExpiry() {
        PasswordResetRequestedEvent requested = new PasswordResetRequestedEvent(
                new PasswordResetRequestedEvent.AccountId("acc-7"),
                new PasswordResetRequestedEvent.Email("forgetful@example.com"),
                "reset-tok-abc", 30, Instant.now());

        dispatch(PASSWORD_RESET_REQUESTED, requested);

        EmailMessage sent = captureSent();
        assertThat(sent.to()).isEqualTo("forgetful@example.com");
        assertThat(sent.htmlBody())
                .contains("http://localhost:5173/reset-password?token=reset-tok-abc")
                .contains("30");
        assertThat(sent.textBody()).contains("http://localhost:5173/reset-password?token=reset-tok-abc");
    }

    /** A live reset link must never be persisted into a feed that outlives the token. */
    @Test
    void send_passwordResetRequested_neverWritesAnInAppNotificationRecord() {
        PasswordResetRequestedEvent requested = new PasswordResetRequestedEvent(
                new PasswordResetRequestedEvent.AccountId("acc-7"),
                new PasswordResetRequestedEvent.Email("forgetful@example.com"),
                "reset-tok-abc", 30, Instant.now());

        dispatch(PASSWORD_RESET_REQUESTED, requested);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void send_alreadyProcessedEvent_sendsNothingAndDoesNotReMarkIt() {
        when(processedEventRepository.existsByEventId("evt-1")).thenReturn(true);

        service.send("evt-1", PAYMENT_SUCCEEDED, null);

        verifyNoInteractions(emailSenderPort);
        verify(processedEventRepository, never()).markProcessed(any(), any());
    }

    private void dispatch(String topic, Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.of(topic, payload, null);
        when(processedEventRepository.existsByEventId(envelope.getEventId())).thenReturn(false);
        service.send(envelope.getEventId(), envelope.getEventType(), envelope.getPayload());
        verify(processedEventRepository).markProcessed(envelope.getEventId(), topic);
    }

    private EmailMessage captureSent() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSenderPort).send(captor.capture());
        return captor.getValue();
    }

    private Notification captureSavedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        return captor.getValue();
    }
}
