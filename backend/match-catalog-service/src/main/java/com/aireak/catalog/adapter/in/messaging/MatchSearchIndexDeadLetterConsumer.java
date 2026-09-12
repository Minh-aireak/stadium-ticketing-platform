package com.aireak.catalog.adapter.in.messaging;

import com.aireak.common.kafka.DeadLetterRecords;
import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Inbound Kafka adapter: alerts on match lifecycle events that {@link MatchSearchIndexConsumer}
 * could not apply to the search index even after {@code KafkaConfig#kafkaErrorHandler}'s
 * retries, and that were republished to the {@code -dlt} topic.
 *
 * <p>A record here means the search index and the database disagree about one match: a
 * published match customers can browse to but not search for, or a cancelled or completed one
 * still turning up in search results. The reconciler is idempotent, so once Elasticsearch is
 * reachable again the record can be replayed as-is — but nothing does that on its own.
 *
 * <p><strong>Log + alert only, no auto-retry</strong>, the stance every dead-letter consumer on
 * the platform takes: a record that already exhausted its retries either failed on something
 * transient a human should confirm is resolved, or replays identically forever.
 *
 * <p>These three {@code -dlt} topics are shared with booking-service and ticket-inventory-service,
 * which consume {@code catalog.match.cancelled} and {@code catalog.match.completed} for their own
 * reasons; a dead-letter topic belongs to a topic, not a service, so their failures land here too.
 * {@link DeadLetterRecords} reads the consumer group off the record: this class speaks only for
 * {@link MatchSearchIndexConsumer#GROUP_ID}, and a record with no group header is reported
 * regardless, because going quiet on it would be the one failure a dead-letter consumer exists
 * to rule out.
 */
@Slf4j
@Component
public class MatchSearchIndexDeadLetterConsumer {

    private static final Set<String> OWN_CONSUMER_GROUPS = Set.of(MatchSearchIndexConsumer.GROUP_ID);

    @KafkaListener(
            topics = {
                    KafkaTopics.MATCH_PUBLISHED + "-dlt",
                    KafkaTopics.MATCH_CANCELLED + "-dlt",
                    KafkaTopics.MATCH_COMPLETED + "-dlt"
            },
            groupId = "match-catalog-service-search-index-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        if (!DeadLetterRecords.deadLetteredBy(record, OWN_CONSUMER_GROUPS)) {
            log.info("Dead-lettered match lifecycle record belongs to consumer group {}, not the "
                            + "search indexer — the service owning that group reports it: "
                            + "topic={}, partition={}, offset={}, key={}",
                    DeadLetterRecords.originalConsumerGroup(record),
                    record.topic(), record.partition(), record.offset(), record.key());
            return;
        }
        log.error("ALERT: match lifecycle event landed on dead-letter topic after exhausting retries — "
                        + "the search index no longer matches the database for this match and needs a "
                        + "manual replay once Elasticsearch is reachable: "
                        + "cause={}, topic={}, partition={}, offset={}, key={}, value={}",
                DeadLetterRecords.failureCause(record),
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
