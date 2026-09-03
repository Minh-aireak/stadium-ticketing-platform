package com.aireak.booking.adapter.in.messaging;

import com.aireak.common.kafka.DeadLetterRecords;
import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Inbound Kafka adapter: alerts on match-cancellation events that {@link MatchCancelledConsumer}
 * could not apply even after {@code KafkaConfig#kafkaErrorHandler}'s retries and were republished
 * to the {@code -dlt} topic.
 *
 * <p>A record here means a match was called off and some of its bookings were never told. Every
 * booking still active for those showtimes stays CONFIRMED or PENDING_PAYMENT, holding seats for a
 * match that will not happen, and the ones already paid for are owed a refund that no longer has
 * anything to trigger it — {@code BookingOrchestrationService#cancelBookingsForShowtime} is the
 * only thing that raises it.
 *
 * <p><strong>Some, not all</strong>, and the distinction is the operator's whole starting point.
 * Neither {@link MatchCancelledConsumer}'s loop over showtimes nor
 * {@code cancelBookingsForShowtime}'s loop over bookings has a per-item try/catch — deliberately,
 * since a swallowed failure here would lose a refund in silence, and throwing is what brings the
 * record to this class. So a failure part way through leaves everything before it already
 * CANCELLED with its {@code RefundRequestedEvent} already in the outbox. Working out which
 * bookings for this match are still active is the first thing to do with this alert, not a
 * re-drive of the whole event; see
 * {@code BookingOrchestrationServiceTest#aFailurePartWayThroughLeavesTheBookingsBeforeItAlreadyCancelled}.
 *
 * <p>Unlike a lost payment result, nothing reconciles this on its own.
 * {@code BookingReconciliationJob} asks payment-service for the outcome of a payment; it has no
 * notion of a match being cancelled and would not disturb these bookings. Until this alert existed,
 * a dead-lettered cancellation was the one saga break in this service with neither a backstop job
 * nor a log line pointing at it.
 *
 * <p><strong>Log + alert only, no auto-retry</strong>, for the same reason
 * {@link PaymentResultDeadLetterConsumer} takes that stance: a poison-pill payload replays
 * identically forever, and re-driving cancellations behind
 * {@code BookingOrchestrationService}'s back risks re-issuing refunds.
 *
 * <p><strong>Everything above describes {@link MatchCancelledConsumer}'s failures, and this topic
 * carries more than those.</strong> ticket-inventory-service consumes
 * {@code catalog.match.cancelled} too, to warm the bookability cache in front of catalog-service,
 * and a {@code -dlt} topic belongs to a topic rather than to a service — so its failures arrive
 * here as well, where the sentence above is false about every one of them: no booking of theirs
 * went uncancelled, no refund of theirs went unrequested, and the cost is a redundant REST call
 * per hold. {@link DeadLetterRecords} reads the consumer group off the record to tell them apart;
 * ticket-inventory-service's own {@code CatalogCacheDeadLetterConsumer} is what reports its half.
 */
@Slf4j
@Component
public class MatchCancelledDeadLetterConsumer {

    private static final Set<String> OWN_CONSUMER_GROUPS = Set.of(MatchCancelledConsumer.GROUP_ID);

    @KafkaListener(
            topics = KafkaTopics.MATCH_CANCELLED + "-dlt",
            groupId = "booking-service-match-cancelled-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        if (!DeadLetterRecords.deadLetteredBy(record, OWN_CONSUMER_GROUPS)) {
            log.info("Dead-lettered match-cancelled record belongs to consumer group {}, not this "
                            + "service's — the service owning that group reports it: "
                            + "topic={}, partition={}, offset={}, key={}",
                    DeadLetterRecords.originalConsumerGroup(record),
                    record.topic(), record.partition(), record.offset(), record.key());
            return;
        }

        log.error("ALERT: match-cancelled event landed on dead-letter topic after exhausting retries — "
                        + "some bookings for a cancelled match were NOT cancelled and the refunds they are "
                        + "owed were NOT requested; the event may have been applied part way, so check which "
                        + "bookings for these showtimes are still active rather than assuming none were: "
                        + "cause={}, topic={}, partition={}, offset={}, key={}, value={}",
                DeadLetterRecords.failureCause(record),
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
