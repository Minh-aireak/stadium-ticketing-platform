package com.aireak.catalog.adapter.in.messaging;

import com.aireak.catalog.application.port.in.ApplyReturnedSeatsUseCase;
import com.aireak.catalog.application.port.in.ApplySoldSeatsUseCase;
import com.aireak.catalog.config.KafkaConfig;
import com.aireak.common.event.EventEnvelope;
import com.aireak.inventory.domain.event.SeatsReturnedEvent;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
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
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.aireak.common.kafka.KafkaTopics.SEATS_SOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link SeatsSoldEventConsumer} through a REAL embedded Kafka broker rather than calling
 * the use case directly — same template as ticket-inventory-service's
 * {@code ShowtimeAddedEventConsumerEmbeddedKafkaIntegrationTest}. Two things only exist at this
 * layer and are invisible to a unit test:
 *
 * <ul>
 *   <li>the {@code @KafkaListener} container wiring and the real {@code JacksonJsonDeserializer}
 *       resolving {@code EventEnvelope.payload}'s polymorphism against actual JSON bytes;</li>
 *   <li>{@code KafkaConfig#kafkaErrorHandler} — retry then dead-letter. Without a bean that
 *       overrides it, Spring Kafka's default silently drops a permanently failing record after ten
 *       instant retries, and a sold-seat event dropped that way leaves {@code available_seats}
 *       short forever. {@link #aPermanentlyFailingProjectionRetriesThenLandsOnTheDeadLetterTopic}
 *       is what proves the override is actually in effect.</li>
 * </ul>
 *
 * <p>Records are produced as a plain UTF-8 string with NO Spring Kafka type headers — which is
 * exactly what Debezium's outbox {@code EventRouter} forwards in production.
 */
@SpringBootTest(classes = {
        KafkaConfig.class,
        SeatsSoldEventConsumer.class,
        SeatsSoldDeadLetterConsumer.class,
        SeatsSoldEventConsumerEmbeddedKafkaIntegrationTest.EnableKafkaListenersConfig.class
})
@EmbeddedKafka(
        partitions = 1,
        topics = {SEATS_SOLD, SEATS_SOLD + "-dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class SeatsSoldEventConsumerEmbeddedKafkaIntegrationTest {

    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules(SeatsSoldEventConsumerEmbeddedKafkaIntegrationTest.class.getClassLoader())
            .build();

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private ApplySoldSeatsUseCase applySoldSeatsUseCase;

    @MockitoBean
    private ApplyReturnedSeatsUseCase applyReturnedSeatsUseCase;

    private KafkaTemplate<String, String> rawProducer;
    private DefaultKafkaProducerFactory<String, String> producerFactory;

    @BeforeEach
    void setUpRawProducer() {
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
    void consumesARealSoldSeatRecordAndProjectsTheSeatCount() {
        SeatsSoldEvent event = new SeatsSoldEvent("showtime-1", List.of("A1", "A2"), Instant.now());
        String eventId = publishAsDebeziumWouldForwardTheOutboxRow(event);

        verify(applySoldSeatsUseCase, timeout(10_000))
                .applySoldSeats(eq(eventId), eq("showtime-1"), eq(2));
    }

    /**
     * A return rides the sale topic (see KafkaTopics#SEATS_RETURNED), so the production deserializer
     * has to resolve the second payload class on it, and the one listener has to route it.
     */
    @Test
    void consumesARealReturnedSeatRecordFromTheSameTopic() {
        SeatsReturnedEvent event = new SeatsReturnedEvent("showtime-3", "booking-9", List.of("C1"), Instant.now());
        String eventId = publishAsDebeziumWouldForwardTheOutboxRow(event);

        verify(applyReturnedSeatsUseCase, timeout(10_000))
                .applyReturnedSeats(eq(eventId), eq("showtime-3"), eq(1));
    }

    /**
     * The reason {@code kafkaErrorHandler} exists. A projection that keeps failing must be retried
     * with a real back-off and then parked somewhere a human can find it — never retried ten times
     * in a millisecond and discarded, which is what the framework default would do.
     */
    @Test
    void aPermanentlyFailingProjectionRetriesThenLandsOnTheDeadLetterTopic() {
        when(applySoldSeatsUseCase.applySoldSeats(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("postgres down"));
        SeatsSoldEvent event = new SeatsSoldEvent("showtime-2", List.of("B7"), Instant.now());
        String eventId = publishAsDebeziumWouldForwardTheOutboxRow(event);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "dlt-verifier-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        try (Consumer<String, String> dltConsumer =
                     new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, SEATS_SOLD + "-dlt");

            ConsumerRecord<String, String> dltRecord =
                    KafkaTestUtils.getSingleRecord(dltConsumer, SEATS_SOLD + "-dlt", Duration.ofSeconds(45));
            assertThat(dltRecord.value()).contains(eventId);
        }
        // At least twice: proves the record was genuinely retried rather than dead-lettered on the
        // first throw, which is the half of the error handler a DLT assertion alone would not catch.
        verify(applySoldSeatsUseCase, atLeast(2)).applySoldSeats(eq(eventId), eq("showtime-2"), eq(1));
    }

    private String publishAsDebeziumWouldForwardTheOutboxRow(Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.of(SEATS_SOLD, payload, null);
        rawProducer.send(new ProducerRecord<>(
                SEATS_SOLD, envelope.getEventId(), OBJECT_MAPPER.writeValueAsString(envelope)));
        return envelope.getEventId();
    }

    @Configuration
    @EnableKafka
    static class EnableKafkaListenersConfig {
    }
}
