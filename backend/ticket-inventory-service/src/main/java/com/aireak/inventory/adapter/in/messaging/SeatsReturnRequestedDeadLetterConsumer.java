package com.aireak.inventory.adapter.in.messaging;

import com.aireak.common.kafka.DeadLetterRecords;
import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: alerts on seat-return requests {@link SeatsReturnRequestedConsumer} could
 * not apply even after {@code KafkaConfig#kafkaErrorHandler}'s retries.
 *
 * <p>A record here means seats a customer cancelled — and has been refunded for — are still SOLD
 * to their booking (or held by it, until the hold expires), so they cannot be sold again. Replaying
 * it is safe, the use case is idempotent; something has to do the replaying.
 *
 * <p><strong>Log + alert only, no auto-retry</strong>, for the reason every dead-letter consumer on
 * this platform gives: a record that exhausted its retries needs a human to see why first.
 */
@Slf4j
@Component
public class SeatsReturnRequestedDeadLetterConsumer {

    @KafkaListener(
            topics = KafkaTopics.SEATS_RETURN_REQUESTED + "-dlt",
            groupId = "ticket-inventory-service-booking-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("ALERT: seat-return request landed on dead-letter topic after exhausting retries — the "
                        + "cancelled seats are still SOLD/held and cannot be resold until it is replayed: "
                        + "cause={}, topic={}, partition={}, offset={}, key={}, value={}",
                DeadLetterRecords.failureCause(record),
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
