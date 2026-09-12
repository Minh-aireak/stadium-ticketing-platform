package com.aireak.catalog.adapter.in.messaging;

import com.aireak.catalog.application.port.in.ReconcileMatchSearchIndexUseCase;
import com.aireak.catalog.domain.event.MatchCancelledEvent;
import com.aireak.catalog.domain.event.MatchCompletedEvent;
import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: this service consuming its own match lifecycle events to keep the
 * Elasticsearch read model in step with Postgres. See {@code MatchSearchIndexReconciler} for why
 * the index is written from here and not from the publishing request.
 *
 * <p>All three topics on one listener because the reaction is the same for each: hand the match
 * id to the reconciler, which re-reads the match and writes the index from what it finds. The
 * event's own fields are deliberately not used — a stale payload would put a stale document in
 * the index, while a re-read cannot.
 *
 * <p>Its own consumer group, distinct from the one {@code SeatsSoldEventConsumer} uses, so that a
 * failing index write retries and dead-letters on its own and never holds up the sold-seat
 * projection. {@code auto-offset-reset=earliest} (the platform default in
 * {@code AbstractKafkaConsumerConfig}) means a group that has never committed — a first deploy,
 * or an operator resetting it — replays every lifecycle event still retained and thereby rebuilds
 * the index from history; the reconciler makes every replay converge on the current row.
 *
 * <p>The failure this cannot catch on its own is Elasticsearch staying down longer than the
 * error handler's 30-second retry budget: the record is then parked on the {@code -dlt} topic
 * and {@code MatchSearchIndexDeadLetterConsumer} reports it, and replaying it once the cluster is
 * back is someone's job — the same stance as every other dead-letter on the platform.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchSearchIndexConsumer {

    static final String GROUP_ID = "match-catalog-service-search-index";

    private final ReconcileMatchSearchIndexUseCase reconcileMatchSearchIndexUseCase;

    @KafkaListener(
            topics = {
                    KafkaTopics.MATCH_PUBLISHED,
                    KafkaTopics.MATCH_CANCELLED,
                    KafkaTopics.MATCH_COMPLETED
            },
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        String matchId = switch (envelope.getPayload()) {
            case MatchPublishedEvent event -> event.matchId();
            case MatchCancelledEvent event -> event.matchId();
            case MatchCompletedEvent event -> event.matchId();
            case null, default -> null;
        };
        if (matchId == null) {
            log.warn("Unexpected payload on a match lifecycle topic: eventId={}, type={}",
                    envelope.getEventId(),
                    envelope.getPayload() == null ? "null" : envelope.getPayload().getClass());
            return;
        }
        reconcileMatchSearchIndexUseCase.reconcile(matchId);
    }
}
