package com.aireak.inventory.adapter.in.messaging;

import com.aireak.catalog.domain.event.ShowtimeAddedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.inventory.application.port.in.GenerateSeatMapUseCase;
import com.aireak.inventory.config.KafkaConfig;
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
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.aireak.common.kafka.KafkaTopics.SHOWTIME_CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link ShowtimeAddedEventConsumer} through a REAL embedded Kafka broker instead of
 * calling {@code generate(...)} on the use case directly — same rationale/template as
 * notification-service's {@code NotificationEventConsumersEmbeddedKafkaIntegrationTest} (see
 * that class's javadoc, and the project memory on the four Kafka deserialization bugs it
 * uncovered): this is the only layer that exercises {@code @KafkaListener} container wiring and
 * the real {@code JacksonJsonDeserializer} resolving {@code EventEnvelope.payload}'s
 * {@code @JsonTypeInfo(use = Id.CLASS)} polymorphism against real JSON bytes.
 *
 * <p>Records are produced as a plain UTF-8 string with NO Spring Kafka type headers — mirrors
 * exactly what Debezium's outbox {@code EventRouter} forwards in production (see
 * match-catalog-service's {@code infra/debezium/catalog-outbox-connector.json}).
 */
@SpringBootTest(classes = {
        KafkaConfig.class,
        ShowtimeAddedEventConsumer.class,
        ShowtimeAddedEventConsumerEmbeddedKafkaIntegrationTest.EnableKafkaListenersConfig.class
})
@EmbeddedKafka(
        partitions = 1,
        topics = {SHOWTIME_CREATED, SHOWTIME_CREATED + "-dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class ShowtimeAddedEventConsumerEmbeddedKafkaIntegrationTest {

    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules(ShowtimeAddedEventConsumerEmbeddedKafkaIntegrationTest.class.getClassLoader())
            .build();

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private GenerateSeatMapUseCase generateSeatMapUseCase;

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
    void consumesRealShowtimeAddedRecord_andGeneratesTheSeatMapWithTheDeserializedFields() {
        ShowtimeAddedEvent event = new ShowtimeAddedEvent(
                "match-1", "showtime-1", 25, new BigDecimal("150000"), "VND", java.time.Instant.now());
        publishAsDebeziumWouldForwardTheOutboxRow(SHOWTIME_CREATED, event);

        verify(generateSeatMapUseCase, timeout(10_000))
                .generate(eq("showtime-1"), eq(25), eq(new BigDecimal("150000")));
    }

    @Test
    void aPermanentlyFailingRecord_retriesThenLandsOnTheDeadLetterTopic() {
        doThrow(new RuntimeException("db down"))
                .when(generateSeatMapUseCase).generate(anyString(), anyInt(), org.mockito.ArgumentMatchers.any());
        ShowtimeAddedEvent event = new ShowtimeAddedEvent(
                "match-2", "showtime-2", 10, new BigDecimal("100000"), "VND", java.time.Instant.now());
        String eventId = publishAsDebeziumWouldForwardTheOutboxRow(SHOWTIME_CREATED, event);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "dlt-verifier-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        try (Consumer<String, String> dltConsumer =
                new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, SHOWTIME_CREATED + "-dlt");

            ConsumerRecord<String, String> dltRecord =
                    KafkaTestUtils.getSingleRecord(dltConsumer, SHOWTIME_CREATED + "-dlt", Duration.ofSeconds(45));
            assertThat(dltRecord.value()).contains(eventId);
        }
        verify(generateSeatMapUseCase, atLeast(2))
                .generate(eq("showtime-2"), anyInt(), org.mockito.ArgumentMatchers.any());
    }

    private String publishAsDebeziumWouldForwardTheOutboxRow(String topic, Object payload) {
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
