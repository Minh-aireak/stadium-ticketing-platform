package com.aireak.booking.adapter.in.messaging;

import com.aireak.common.kafka.DeadLetterRecords;
import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Inbound Kafka adapter: alerts on payment result records that {@link PaymentResultConsumer}
 * could not process even after {@code KafkaConfig#kafkaErrorHandler}'s retries and were
 * republished to the {@code -dlt} topic (see {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}).
 *
 * <p>A record here means a booking is stuck: {@code PaymentResultConsumer} never applied its
 * outcome, so {@code BookingReconciliationJob}'s payment-service query is the only remaining path
 * to resolving it (this class does not retry the record itself — see class-level "no auto-retry"
 * scope note below).
 *
 * <p><strong>Log + alert only, no auto-retry</strong>: replaying a record that already exhausted
 * retries needs a human to first understand why it failed (a poison-pill payload replays
 * identically forever; a transient failure may already be resolved) — reprocessing it
 * automatically here would risk either a tight failure loop or double-applying a saga step behind
 * {@code BookingOrchestrationService}'s back. {@code BookingReconciliationJob} already provides
 * the safe, idempotent path back to a resolved outcome.
 *
 * <p><strong>Everything above describes {@link PaymentResultConsumer}'s failures, and
 * {@code payment.payment.succeeded-dlt} carries more than those.</strong> notification-service
 * consumes the same topic to send the receipt email, and a {@code -dlt} topic belongs to a topic
 * rather than to a service — so a failed receipt arrives here too, where "a booking may be stuck
 * in PENDING_PAYMENT" is false: this service confirmed the booking from its own copy of that
 * record. {@link DeadLetterRecords} reads the consumer group off the record to tell them apart;
 * notification-service's own {@code NotificationDeadLetterConsumer} is what reports its half.
 */
@Slf4j
@Component
public class PaymentResultDeadLetterConsumer {

    private static final Set<String> OWN_CONSUMER_GROUPS = Set.of(PaymentResultConsumer.GROUP_ID);

    @KafkaListener(
            topics = {KafkaTopics.PAYMENT_SUCCEEDED + "-dlt", KafkaTopics.PAYMENT_FAILED + "-dlt"},
            groupId = "booking-service-payment-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        if (!DeadLetterRecords.deadLetteredBy(record, OWN_CONSUMER_GROUPS)) {
            log.info("Dead-lettered payment result record belongs to consumer group {}, not this "
                            + "service's — the service owning that group reports it: "
                            + "topic={}, partition={}, offset={}, key={}",
                    DeadLetterRecords.originalConsumerGroup(record),
                    record.topic(), record.partition(), record.offset(), record.key());
            return;
        }

        log.error("ALERT: payment result event landed on dead-letter topic after exhausting retries — "
                        + "a booking may be stuck in PENDING_PAYMENT: cause={}, topic={}, partition={}, "
                        + "offset={}, key={}, value={}",
                DeadLetterRecords.failureCause(record),
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
