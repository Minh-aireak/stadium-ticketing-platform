package com.aireak.notification.application.service;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.notification.application.port.out.EmailSenderPort;
import com.aireak.notification.application.port.out.NotificationRepository;
import com.aireak.notification.application.port.out.ProcessedEventRepository;
import com.aireak.notification.application.port.out.SmsSenderPort;
import com.aireak.notification.domain.model.Notification;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * Exercises the notification-service Kafka consumer flow at the unit level using the same
 * {@link EventEnvelope} shape producers actually build: {@code OutboxEventPublisher} (and
 * {@code PaymentEventPublisher}/{@code CatalogEventPublisher}) now call
 * {@code EventEnvelope.of(topic, domainEvent, traceId)}, so {@code eventType} is the
 * {@code KafkaTopics} routing string this service's TEMPLATES map is keyed by — not a Java
 * class name. {@code BookingEventConsumer}/{@code AccountEventConsumer} hand that value straight
 * to {@link NotificationDispatchService#send}.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock
    private EmailSenderPort emailSenderPort;
    @Mock
    private SmsSenderPort smsSenderPort;
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

        service = new NotificationDispatchService(
                emailSenderPort, smsSenderPort, processedEventRepository, notificationRepository, freemarkerConfig);
    }

    @Test
    void send_confirmedBookingAsProducedInProduction_rendersRealTemplateWithAmountAndCurrency() {
        BookingConfirmedEvent bookingConfirmed = new BookingConfirmedEvent(
                "booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                List.of("A1", "A2"),
                new BookingConfirmedEvent.BookingAmount(new BigDecimal("100.00"), "VND"),
                Instant.now());
        EventEnvelope<BookingConfirmedEvent> envelope =
                EventEnvelope.of(BOOKING_CONFIRMED, bookingConfirmed, null);

        service.send(envelope.getEventId(), envelope.getEventType(), envelope.getPayload());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSenderPort).send(to.capture(), anyString(), body.capture());
        assertThat(to.getValue()).isEqualTo("customer-1@example.com");
        assertThat(body.getValue())
                .doesNotContain("Failed to render")
                .contains("100")
                .contains("VND");

        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notification.capture());
        assertThat(notification.getValue().getRecipientId()).isEqualTo("customer-1");
        assertThat(notification.getValue().isRead()).isFalse();
    }

    @Test
    void send_cancelledBookingAsProducedInProduction_rendersRealTemplate() {
        BookingCancelledEvent cancelled = new BookingCancelledEvent(
                "booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                "customer requested refund", Instant.now());
        EventEnvelope<BookingCancelledEvent> envelope =
                EventEnvelope.of(BOOKING_CANCELLED, cancelled, null);

        service.send(envelope.getEventId(), envelope.getEventType(), envelope.getPayload());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSenderPort).send(to.capture(), anyString(), body.capture());
        assertThat(to.getValue()).isEqualTo("customer-1@example.com");
        assertThat(body.getValue())
                .doesNotContain("Failed to render")
                .contains("customer requested refund");
    }

    @Test
    void send_accountRegisteredAsProducedInProduction_rendersRealTemplateAndEmailsRegisteredAddress() {
        AccountRegisteredEvent registered = new AccountRegisteredEvent(
                new AccountRegisteredEvent.AccountId("acc-1"),
                new AccountRegisteredEvent.Email("new-user@example.com"),
                "test-verification-token",
                Instant.now());
        EventEnvelope<AccountRegisteredEvent> envelope =
                EventEnvelope.of(ACCOUNT_REGISTERED, registered, null);

        service.send(envelope.getEventId(), envelope.getEventType(), envelope.getPayload());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSenderPort).send(to.capture(), anyString(), body.capture());
        assertThat(to.getValue()).isEqualTo("new-user@example.com");
        assertThat(body.getValue()).doesNotContain("Failed to render");
        assertThat(body.getValue())
                .contains("http://localhost:8081/api/v1/auth/verify-email?token=test-verification-token");

        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notification.capture());
        assertThat(notification.getValue().getRecipientId()).isEqualTo("acc-1");
    }

    @Test
    void send_accountActivatedAsProducedInProduction_rendersRealTemplateAndEmailsTheAccountAddress() {
        AccountActivatedEvent activated = new AccountActivatedEvent(
                new AccountActivatedEvent.AccountId("acc-1"),
                new AccountActivatedEvent.Email("new-user@example.com"),
                Instant.now());
        EventEnvelope<AccountActivatedEvent> envelope =
                EventEnvelope.of(ACCOUNT_ACTIVATED, activated, null);

        service.send(envelope.getEventId(), envelope.getEventType(), envelope.getPayload());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSenderPort).send(to.capture(), anyString(), body.capture());
        assertThat(to.getValue()).isEqualTo("new-user@example.com");
        assertThat(body.getValue()).doesNotContain("Failed to render").contains("acc-1");

        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notification.capture());
        assertThat(notification.getValue().getRecipientId()).isEqualTo("acc-1");
    }
}
