package com.aireak.inventory.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit test for {@link ShowtimeAddedDeadLetterConsumer}.
 *
 * <p>Confirms that the DLT listener method handles incoming DLT records cleanly without throwing,
 * whether the record is valid JSON or unparsable raw text.
 */
class ShowtimeAddedDeadLetterConsumerTest {

    private final ShowtimeAddedDeadLetterConsumer consumer = new ShowtimeAddedDeadLetterConsumer();

    @Test
    void doesNotThrowForDeadLetteredShowtimeCreatedRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SHOWTIME_CREATED + "-dlt", 0, 0L, "showtime-1", "{\"eventType\":\"catalog.showtime.created\"}");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowForDeadLetteredRecordWithUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SHOWTIME_CREATED + "-dlt", 0, 0L, "showtime-2", "not valid json payload");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }
}
