package com.aireak.booking.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit test for {@link MatchCancelledDeadLetterConsumer}.
 *
 * <p>Confirms the DLT listener handles a record cleanly whether its value is valid JSON or the
 * raw bytes of a payload that never deserialized — the second case is why the dead-letter
 * container factory is String-typed at all (see {@code AbstractKafkaConsumerConfig}), and a
 * listener that threw on it would take down the one thing reporting the failure.
 */
class MatchCancelledDeadLetterConsumerTest {

    private final MatchCancelledDeadLetterConsumer consumer = new MatchCancelledDeadLetterConsumer();

    @Test
    void doesNotThrowForDeadLetteredMatchCancelledRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_CANCELLED + "-dlt", 0, 0L, "match-1",
                "{\"eventType\":\"catalog.match.cancelled\"}");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowForDeadLetteredRecordWithUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_CANCELLED + "-dlt", 0, 0L, "match-2", "not valid json payload");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }
}
