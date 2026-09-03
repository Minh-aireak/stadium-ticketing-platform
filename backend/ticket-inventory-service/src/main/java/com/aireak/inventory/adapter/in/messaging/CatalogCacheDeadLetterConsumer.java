package com.aireak.inventory.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: alerts on match cancelled/completed events that
 * {@link MatchCancelledEventConsumer} and {@link MatchCompletedEventConsumer} could not process
 * even after {@code KafkaConfig#kafkaErrorHandler}'s retries, and that were republished to the
 * {@code -dlt} topic.
 *
 * <p>This service consumes three topics and, until this class existed, watched the dead-letter
 * side of one: {@link ShowtimeAddedDeadLetterConsumer} covers {@code SHOWTIME_CREATED} and these
 * two had nobody. A record dead-lettered on either behaved exactly as it did before any
 * dead-letter consumer was written here — retried, republished, and then reported by nothing.
 *
 * <p><strong>Separate from {@link ShowtimeAddedDeadLetterConsumer} rather than two more entries on
 * its topic list</strong>, because the two consequences are not the same and its ERROR line names
 * the wrong one. A dead-lettered showtime means a seat map was never generated and the showtime
 * cannot be sold at all. These two only warm the local cache
 * {@code ShowtimeCatalogRestAdapter} keeps in front of catalog-service: {@code requireBookable}
 * falls through to the authoritative REST call whenever nothing is cached for a showtime, so
 * losing one of these costs a redundant round trip on every hold and reserve for that match —
 * never an incorrect booking, and never a seat sold for a match that will not happen.
 *
 * <p>WARN rather than ERROR for that reason: this is a degraded fast path, not money or seats
 * lost, and it should not read at 3am like the sibling alerts do. What it must not be is silent,
 * because a match cancelled during a busy sale sends every hold on those showtimes back through
 * catalog-service — the redundant round trips arrive exactly when the REST call is most expensive,
 * and nothing else in the system would say why.
 *
 * <p><strong>Log + alert only, no auto-retry</strong>, the stance every dead-letter consumer on
 * the platform takes: a poison payload replays identically forever.
 */
@Slf4j
@Component
public class CatalogCacheDeadLetterConsumer {

    @KafkaListener(
            topics = {
                    KafkaTopics.MATCH_CANCELLED + "-dlt",
                    KafkaTopics.MATCH_COMPLETED + "-dlt"
            },
            groupId = "ticket-inventory-service-catalog-cache-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.warn("Match lifecycle event landed on dead-letter topic after exhausting retries — "
                        + "the local bookability cache was not updated, so holds and reservations for "
                        + "these showtimes fall through to catalog-service until it is: "
                        + "topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
