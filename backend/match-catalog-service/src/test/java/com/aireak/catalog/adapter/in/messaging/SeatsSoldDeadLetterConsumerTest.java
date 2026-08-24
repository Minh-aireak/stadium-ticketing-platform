package com.aireak.catalog.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit test for {@link SeatsSoldDeadLetterConsumer}.
 *
 * <p>Confirms the DLT listener handles whatever lands on the topic without throwing. Both shapes
 * matter: a record can arrive there because the projection failed (the value is well-formed
 * envelope JSON) or because deserialization itself failed (the value is whatever raw bytes came
 * in). A listener that threw on the second case would take its own container down — and the alert
 * with it.
 */
class SeatsSoldDeadLetterConsumerTest {

    private final SeatsSoldDeadLetterConsumer consumer = new SeatsSoldDeadLetterConsumer();

    @Test
    void doesNotThrowForADeadLetteredSoldSeatRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SEATS_SOLD + "-dlt", 0, 0L, "showtime-1",
                "{\"eventType\":\"inventory.seats.sold\"}");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowForADeadLetteredRecordWithAnUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SEATS_SOLD + "-dlt", 0, 0L, "showtime-2", "not valid json payload");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }
}
