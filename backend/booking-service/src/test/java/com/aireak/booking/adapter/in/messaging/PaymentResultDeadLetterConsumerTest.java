package com.aireak.booking.adapter.in.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static com.aireak.common.kafka.KafkaTopics.PAYMENT_FAILED;
import static com.aireak.common.kafka.KafkaTopics.PAYMENT_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * This consumer's only job is to log + alert (see class javadoc for why it doesn't retry) —
 * these tests just confirm it never throws, regardless of whether the dead-lettered value
 * happens to be valid JSON or the raw bytes of a payload that failed to deserialize.
 */
class PaymentResultDeadLetterConsumerTest {

    private final PaymentResultDeadLetterConsumer consumer = new PaymentResultDeadLetterConsumer();

    @Test
    void doesNotThrowForADeadLetteredPaymentSucceededRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                PAYMENT_SUCCEEDED + "-dlt", 0, 0L, "booking-1", "{\"eventType\":\"payment.payment.succeeded\"}");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowForADeadLetteredPaymentFailedRecordWithUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                PAYMENT_FAILED + "-dlt", 0, 0L, "booking-2", "not valid json");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }
}
