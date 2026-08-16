package com.aireak.booking.adapter.in.messaging;

import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.booking.config.KafkaConfig;
import com.aireak.common.event.EventEnvelope;
import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.aireak.common.kafka.KafkaTopics.PAYMENT_FAILED;
import static com.aireak.common.kafka.KafkaTopics.PAYMENT_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link PaymentResultConsumer} through a REAL embedded Kafka broker instead of calling
 * {@code consume(envelope)} directly (see {@link PaymentResultConsumerTest} in this same package
 * for that method-level unit coverage, which is kept as-is). This exercises the parts a direct
 * method call skips entirely: the {@code @KafkaListener} container wiring, the real
 * {@link org.springframework.kafka.support.serializer.JacksonJsonDeserializer} configured in
 * {@link KafkaConfig}, and {@code DefaultErrorHandler}/dead-letter-topic behavior on a record
 * that keeps failing.
 *
 * <p>Records are produced here as a plain UTF-8 string with NO Spring Kafka type headers — that
 * is deliberate, not an oversight. In production these topics are populated by Debezium's outbox
 * {@code EventRouter} (see {@code booking-service/infra/debezium/booking-outbox-connector.json}
 * and payment-service's equivalent), which forwards the {@code outbox_events.payload} column
 * verbatim and never sets a {@code __TypeId__} header. Manually adding that header here to make
 * deserialization "work" would validate a wire format production never actually sends — which is
 * exactly how {@code KafkaConfig}'s deserializer shipped without a default target type and broke
 * every record from this topic. Kafka Connect itself (Debezium + JsonConverter) stays out of
 * scope for this Spring-context test, the same boundary {@code OutboxEventPublisherIntegrationTest}
 * already draws — see infra/debezium/README.md for the manual end-to-end CDC check.
 */
@SpringBootTest(classes = {
        KafkaConfig.class,
        PaymentResultConsumer.class,
        PaymentResultConsumerEmbeddedKafkaIntegrationTest.EnableKafkaListenersConfig.class
})
@EmbeddedKafka(
        partitions = 1,
        topics = {PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_SUCCEEDED + "-dlt", PAYMENT_FAILED + "-dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class PaymentResultConsumerEmbeddedKafkaIntegrationTest {

    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules(PaymentResultConsumerEmbeddedKafkaIntegrationTest.class.getClassLoader())
            .build();

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @MockitoBean
    private BookingOrchestrationService bookingOrchestrationService;

    private KafkaTemplate<String, String> rawProducer;
    private DefaultKafkaProducerFactory<String, String> producerFactory;

    @BeforeEach
    void setUpRawProducer() {
        // Plain String producer — mirrors how Debezium forwards the outbox payload column, not
        // KafkaConfig's own JacksonJsonSerializer-based deadLetterKafkaTemplate (that one's for
        // this service's own DLT publishing, not for producing "incoming" test records).
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        // KafkaTestUtils.producerProps() defaults to IntegerSerializer for the key — override both
        // to StringSerializer since these are String eventIds, not partition-hash integers.
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
    void consumesRealPaymentSucceededRecord_andConfirmsTheBooking() {
        String bookingId = "booking-" + UUID.randomUUID();
        PaymentSucceededEvent succeeded = new PaymentSucceededEvent(
                "payment-1", bookingId, "buyer@example.com", new BigDecimal("150.00"), "USD", "gw-txn-1", Instant.now());
        publishAsDebeziumWouldForwardTheOutboxRow(PAYMENT_SUCCEEDED, succeeded);

        verify(bookingOrchestrationService, timeout(10_000)).confirmBooking(bookingId);
    }

    @Test
    void consumesRealPaymentFailedRecord_andCancelsTheBookingWithReasonFromTheEvent() {
        String bookingId = "booking-" + UUID.randomUUID();
        PaymentFailedEvent failed = new PaymentFailedEvent("payment-1", bookingId, "card declined", Instant.now());
        publishAsDebeziumWouldForwardTheOutboxRow(PAYMENT_FAILED, failed);

        verify(bookingOrchestrationService, timeout(10_000))
                .cancelBookingOnPaymentFailure(bookingId, "card declined");
    }

    @Test
    void aPermanentlyFailingRecord_retriesThenLandsOnTheDeadLetterTopic() {
        String bookingId = "booking-" + UUID.randomUUID();
        doThrow(new RuntimeException("downstream boom")).when(bookingOrchestrationService).confirmBooking(any());
        PaymentSucceededEvent succeeded = new PaymentSucceededEvent(
                "payment-1", bookingId, "buyer@example.com", new BigDecimal("150.00"), "USD", "gw-txn-1", Instant.now());
        publishAsDebeziumWouldForwardTheOutboxRow(PAYMENT_SUCCEEDED, succeeded);

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "dlt-verifier-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        try (Consumer<String, String> dltConsumer =
                new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, PAYMENT_SUCCEEDED + "-dlt");

            // KafkaConfig's ExponentialBackOff retries for up to 30s (maxElapsedTime) before the
            // DeadLetterPublishingRecoverer republishes here — generous timeout for CI jitter.
            ConsumerRecord<String, String> dltRecord =
                    KafkaTestUtils.getSingleRecord(dltConsumer, PAYMENT_SUCCEEDED + "-dlt", Duration.ofSeconds(45));
            assertThat(dltRecord.value()).contains(bookingId);
        }
        // Confirms the record was actually retried, not just failed once and forwarded straight away.
        verify(bookingOrchestrationService, atLeast(2)).confirmBooking(bookingId);
    }

    private void publishAsDebeziumWouldForwardTheOutboxRow(String topic, Object payload) {
        // Mirrors OutboxEventPublisher.serialize(): a plain ObjectMapper writing EventEnvelope.of(...)
        // straight to a String — the exact text Debezium's outbox router forwards as the record value.
        EventEnvelope<Object> envelope = EventEnvelope.of(topic, payload, null);
        String json = writeValueAsString(envelope);
        rawProducer.send(new ProducerRecord<>(topic, envelope.getEventId(), json));
    }

    private String writeValueAsString(EventEnvelope<Object> envelope) {
        return OBJECT_MAPPER.writeValueAsString(envelope);
    }

    @Configuration
    @EnableKafka
    static class EnableKafkaListenersConfig {
    }
}
