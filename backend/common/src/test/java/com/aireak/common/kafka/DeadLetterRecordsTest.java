package com.aireak.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link DeadLetterRecords}, the header reader every dead-letter consumer on the
 * platform now goes through.
 *
 * <p>The header names and values here are the ones a real broker delivers, taken from a probe that
 * dead-lettered a record through an embedded Kafka with the platform's own
 * {@code AbstractKafkaConsumerConfig} wiring: the recoverer stamps
 * {@code kafka_dlt-original-consumer-group=booking-service-payment} and a
 * {@code kafka_dlt-exception-cause-fqcn} alongside the wrapper's
 * {@code ListenerExecutionFailedException}.
 */
class DeadLetterRecordsTest {

    private static final String TOPIC = KafkaTopics.PAYMENT_SUCCEEDED + "-dlt";

    @Test
    void readsTheConsumerGroupWhoseListenerFailed() {
        ConsumerRecord<String, String> record = record();
        addHeader(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, "booking-service-payment");

        assertThat(DeadLetterRecords.originalConsumerGroup(record)).isEqualTo("booking-service-payment");
    }

    @Test
    void reportsNoConsumerGroupWhenTheRecordCarriesNone() {
        assertThat(DeadLetterRecords.originalConsumerGroup(record())).isNull();
    }

    @Test
    void attributesARecordToTheGroupThatDeadLetteredIt() {
        ConsumerRecord<String, String> record = record();
        addHeader(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, "booking-service-payment");

        assertThat(DeadLetterRecords.deadLetteredBy(record, Set.of("booking-service-payment"))).isTrue();
        assertThat(DeadLetterRecords.deadLetteredBy(record, Set.of("notification-service-payment"))).isFalse();
    }

    /**
     * The rule that keeps the filter from being a way to lose an alert. A record nobody can
     * attribute has to be reported by whoever sees it; the alternative is every dead-letter
     * consumer on the topic staying quiet about it.
     */
    @Test
    void treatsAnUnattributableRecordAsEveryonesToReport() {
        assertThat(DeadLetterRecords.deadLetteredBy(record(), Set.of("booking-service-payment"))).isTrue();
    }

    @Test
    void prefersTheCauseOverTheListenerExecutionWrapper() {
        ConsumerRecord<String, String> record = record();
        addHeader(record, KafkaHeaders.DLT_EXCEPTION_FQCN,
                "org.springframework.kafka.listener.ListenerExecutionFailedException");
        addHeader(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, "java.lang.IllegalStateException");
        addHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE, "Listener method threw; boom");

        assertThat(DeadLetterRecords.failureCause(record))
                .isEqualTo("java.lang.IllegalStateException: Listener method threw; boom");
    }

    /** A deserialization failure has no cause header — the wrapper is all there is. */
    @Test
    void fallsBackToTheWrapperWhenThereIsNoCause() {
        ConsumerRecord<String, String> record = record();
        addHeader(record, KafkaHeaders.DLT_EXCEPTION_FQCN,
                "org.springframework.kafka.support.serializer.DeserializationException");
        addHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE, "failed to deserialize");

        assertThat(DeadLetterRecords.failureCause(record))
                .isEqualTo("org.springframework.kafka.support.serializer.DeserializationException: "
                        + "failed to deserialize");
    }

    @Test
    void saysSoRatherThanNullWhenTheRecordCarriesNoExceptionHeaders() {
        assertThat(DeadLetterRecords.failureCause(record())).isEqualTo(DeadLetterRecords.UNKNOWN_CAUSE);
    }

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>(TOPIC, 0, 7L, "booking-1", "{}");
    }

    private static void addHeader(ConsumerRecord<String, String> record, String name, String value) {
        record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }
}
