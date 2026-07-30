package com.aireak.inventory.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: alerts on showtime created events that {@link ShowtimeAddedEventConsumer}
 * could not process even after {@code KafkaConfig#kafkaErrorHandler}'s retries and were
 * republished to the {@code -dlt} topic (see {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}).
 *
 * <p>A record here means a showtime seat map was not generated: seat map generation failed permanently,
 * so the showtime has no available seats for booking without manual intervention.
 *
 * <p><strong>Log + alert only, no auto-retry</strong>: replaying a record that already exhausted
 * retries needs a human to understand why it failed — reprocessing it automatically here would
 * risk a tight failure loop.
 */
@Slf4j
@Component
public class ShowtimeAddedDeadLetterConsumer {

    @KafkaListener(
            topics = KafkaTopics.SHOWTIME_CREATED + "-dlt",
            groupId = "ticket-inventory-service-catalog-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("ALERT: showtime event landed on dead-letter topic after exhausting retries — "
                        + "seat map for showtime may not be generated: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
