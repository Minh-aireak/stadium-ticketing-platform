package com.aireak.booking.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
 */
@Slf4j
@Component
public class PaymentResultDeadLetterConsumer {

    @KafkaListener(
            topics = {KafkaTopics.PAYMENT_SUCCEEDED + "-dlt", KafkaTopics.PAYMENT_FAILED + "-dlt"},
            groupId = "booking-service-payment-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("ALERT: payment result event landed on dead-letter topic after exhausting retries — "
                        + "a booking may be stuck in PENDING_PAYMENT: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
