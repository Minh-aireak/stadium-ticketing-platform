package com.aireak.notification.adapter.in.messaging;

import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.notification.application.port.in.SendNotificationUseCase;
import com.aireak.notification.config.KafkaConfig;
import tools.jackson.databind.json.JsonMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.aireak.common.kafka.KafkaTopics.ACCOUNT_REGISTERED;
import static com.aireak.common.kafka.KafkaTopics.BOOKING_CANCELLED;
import static com.aireak.common.kafka.KafkaTopics.BOOKING_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link BookingEventConsumer} and {@link AccountEventConsumer} through a REAL embedded
 * Kafka broker instead of calling {@code send(eventId, eventType, payload)} on the use case
 * directly (see {@code NotificationDispatchServiceTest} for that unit-level coverage, kept as-is).
 * This is the layer those tests cannot reach: {@code @KafkaListener} container wiring, the real
 * {@link org.springframework.kafka.support.serializer.JacksonJsonDeserializer} configured in
 * {@link KafkaConfig} — including resolving {@code EventEnvelope.payload}'s
 * {@code @JsonTypeInfo(use = Id.CLASS)} polymorphism against a real trusted-packages check, not a
 * Java object already constructed in memory — and {@code DefaultErrorHandler}/dead-letter-topic
 * behavior on a record that keeps failing.
 *
 * <p>Records are produced here as a plain UTF-8 string with NO Spring Kafka type headers — that
 * is deliberate, not an oversight. In production these topics are populated by Debezium's outbox
 * {@code EventRouter} (see {@code booking-service}/{@code identity-service}
 * {@code infra/debezium/*.json}), which forwards the {@code outbox_events.payload} column verbatim
 * and never sets a {@code __TypeId__} header — see {@link KafkaConfig}'s {@code consumerFactory()}
 * javadoc for the production bug this exact wire format previously exposed. Kafka Connect itself
 * stays out of scope for this Spring-context test, the same boundary already drawn for the outbox
 * write-side (see each service's infra/debezium/README.md for the manual end-to-end CDC check).
 */
@SpringBootTest(classes = {
        KafkaConfig.class,
        BookingEventConsumer.class,
        AccountEventConsumer.class,
        NotificationEventConsumersEmbeddedKafkaIntegrationTest.EnableKafkaListenersConfig.class
})
@EmbeddedKafka(
        partitions = 1,
        topics = {
                BOOKING_CONFIRMED, BOOKING_CANCELLED, ACCOUNT_REGISTERED,
                BOOKING_CONFIRMED + "-dlt", BOOKING_CANCELLED + "-dlt", ACCOUNT_REGISTERED + "-dlt"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class NotificationEventConsumersEmbeddedKafkaIntegrationTest {

    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules(NotificationEventConsumersEmbeddedKafkaIntegrationTest.class.getClassLoader())
            .build();

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private SendNotificationUseCase sendNotificationUseCase;

    private KafkaTemplate<String, String> rawProducer;
    private DefaultKafkaProducerFactory<String, String> producerFactory;

    @BeforeEach
    void setUpRawProducer() {
        // Plain String producer — mirrors how Debezium forwards the outbox payload column, not
        // KafkaConfig's own JacksonJsonSerializer-based deadLetterKafkaTemplate (that one's for
        // this service's own DLT publishing, not for producing "incoming" test records).
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        rawProducer = new KafkaTemplate<>(producerFactory);
    }

    @AfterEach
    void tearDownRawProducer() {
        producerFactory.destroy();
    }

    @Test
    void consumesRealBookingConfirmedRecord_andDispatchesWithTheDeserializedPayload() {
        BookingConfirmedEvent confirmed = new BookingConfirmedEvent(
                "booking-1", "customer-1", "customer-1@example.com", "showtime-1",
                java.util.List.of("A1", "A2"),
                new BookingConfirmedEvent.BookingAmount(new java.math.BigDecimal("100.00"), "VND"),
                Instant.now());
        String eventId = publishAsDebeziumWouldForwardTheOutboxRow(BOOKING_CONFIRMED, confirmed);

        // Asserting the payload's real type/fields (not just that send() fired at all) proves the
        // record round-tripped through actual JSON bytes and @JsonTypeInfo(use = Id.CLASS)
        // resolution, rather than the deserializer merely handing back a raw Map/LinkedHashMap.
        verify(sendNotificationUseCase, timeout(10_000))
                .send(eq(eventId), eq(BOOKING_CONFIRMED), any(BookingConfirmedEvent.class));
    }

    @Test
    void consumesRealBookingCancelledRecord_andDispatchesWithTheDeserializedPayload() {
        BookingCancelledEvent cancelled = new BookingCancelledEvent(
                "booking-2", "customer-2", "customer-2@example.com", "showtime-2",
                "customer requested refund", Instant.now());
        String eventId = publishAsDebeziumWouldForwardTheOutboxRow(BOOKING_CANCELLED, cancelled);

        verify(sendNotificationUseCase, timeout(10_000))
                .send(eq(eventId), eq(BOOKING_CANCELLED), any(BookingCancelledEvent.class));
    }

    @Test
    void consumesRealAccountRegisteredRecord_andDispatchesWithTheDeserializedPayload() {
        AccountRegisteredEvent registered = new AccountRegisteredEvent(
                new AccountRegisteredEvent.AccountId("acc-1"),
                new AccountRegisteredEvent.Email("new-user@example.com"),
                "test-verification-token",
                Instant.now());
        String eventId = publishAsDebeziumWouldForwardTheOutboxRow(ACCOUNT_REGISTERED, registered);

        verify(sendNotificationUseCase, timeout(10_000))
                .send(eq(eventId), eq(ACCOUNT_REGISTERED), any(AccountRegisteredEvent.class));
    }

    @Test
    void aPermanentlyFailingRecord_retriesThenLandsOnTheDeadLetterTopic() {
        doThrow(new RuntimeException("mail server down"))
                .when(sendNotificationUseCase).send(anyString(), anyString(), any());
        BookingConfirmedEvent confirmed = new BookingConfirmedEvent(
                "booking-3", "customer-3", "customer-3@example.com", "showtime-3",
                java.util.List.of("B1"),
                new BookingConfirmedEvent.BookingAmount(new java.math.BigDecimal("50.00"), "VND"),
                Instant.now());
        String eventId = publishAsDebeziumWouldForwardTheOutboxRow(BOOKING_CONFIRMED, confirmed);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "dlt-verifier-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        try (Consumer<String, String> dltConsumer =
                new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, BOOKING_CONFIRMED + "-dlt");

            // KafkaConfig's ExponentialBackOff retries for up to 30s (maxElapsedTime) before the
            // DeadLetterPublishingRecoverer republishes here — generous timeout for CI jitter.
            ConsumerRecord<String, String> dltRecord =
                    KafkaTestUtils.getSingleRecord(dltConsumer, BOOKING_CONFIRMED + "-dlt", Duration.ofSeconds(45));
            assertThat(dltRecord.value()).contains(eventId);
        }
        // Confirms the record was actually retried, not just failed once and forwarded straight away.
        verify(sendNotificationUseCase, atLeast(2)).send(eq(eventId), eq(BOOKING_CONFIRMED), any());
    }

    private String publishAsDebeziumWouldForwardTheOutboxRow(String topic, Object payload) {
        // Mirrors OutboxEventPublisher.serialize() (booking-service/identity-service): a plain
        // ObjectMapper writing EventEnvelope.of(...) straight to a String — the exact text
        // Debezium's outbox router forwards as the record value.
        EventEnvelope<Object> envelope = EventEnvelope.of(topic, payload, null);
        String json = writeValueAsString(envelope);
        rawProducer.send(new ProducerRecord<>(topic, envelope.getEventId(), json));
        return envelope.getEventId();
    }

    private String writeValueAsString(EventEnvelope<Object> envelope) {
        return OBJECT_MAPPER.writeValueAsString(envelope);
    }

    @Configuration
    @EnableKafka
    static class EnableKafkaListenersConfig {
    }
}
