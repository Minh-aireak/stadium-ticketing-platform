package com.aireak.payment.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: alerts on refund requests {@link RefundRequestedConsumer} could not apply
 * even after {@code KafkaConfig#kafkaErrorHandler}'s retries.
 *
 * <p>A record here means a customer has been charged for something they will not receive and the
 * money has not gone back — the most expensive failure this service has. It is logged at ERROR
 * rather than retried automatically for the same reason booking-service's dead-letter consumer
 * does not retry: a poison payload replays identically forever, and a refund is not something to
 * loop on blindly against a payment gateway.
 */
@Slf4j
@Component
public class RefundRequestedDeadLetterConsumer {

    @KafkaListener(
            topics = KafkaTopics.REFUND_REQUESTED + "-dlt",
            groupId = "payment-service-refund-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("ALERT: refund request landed on dead-letter topic after exhausting retries — "
                        + "a customer is owed money that was NOT refunded: topic={}, partition={}, "
                        + "offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
