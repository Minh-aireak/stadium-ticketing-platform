package com.aireak.catalog.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: alerts on sold-seat events that {@link SeatsSoldEventConsumer} could not
 * project even after {@code KafkaConfig#kafkaErrorHandler}'s retries, and that were republished to
 * the {@code -dlt} topic.
 *
 * <p>A record here means a sale that happened in ticket-inventory-service never reached the
 * catalog's numbers: {@code showtimes.available_seats} is permanently short by that event's seats,
 * and the live Redis counter with it. Nothing self-heals this — the projection is idempotent by
 * event id, so it can be safely replayed once someone has fixed whatever it was failing on, but
 * something has to do the replaying.
 *
 * <p><strong>Log + alert only, no auto-retry</strong>: a record that already exhausted its retries
 * either replays identically forever (a showtime id that does not exist, a payload that will not
 * deserialize) or failed on something transient that a human should confirm is actually resolved.
 * Retrying it automatically here would just move the tight failure loop to a second listener.
 */
@Slf4j
@Component
public class SeatsSoldDeadLetterConsumer {

    @KafkaListener(
            topics = KafkaTopics.SEATS_SOLD + "-dlt",
            groupId = "match-catalog-service-inventory-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("ALERT: sold-seat event landed on dead-letter topic after exhausting retries — "
                        + "this showtime's available_seats is now permanently short by that sale and needs a "
                        + "manual replay: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
