package com.aireak.booking.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: alerts on match-cancellation events that {@link MatchCancelledConsumer}
 * could not apply even after {@code KafkaConfig#kafkaErrorHandler}'s retries and were republished
 * to the {@code -dlt} topic.
 *
 * <p>A record here means a match was called off and its bookings were never told. Every active
 * booking for those showtimes stays CONFIRMED or PENDING_PAYMENT, holding seats for a match that
 * will not happen, and the ones already paid for are owed a refund that no longer has anything to
 * trigger it — {@code BookingOrchestrationService#cancelBookingsForShowtime} is the only thing
 * that raises it.
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
 */
@Slf4j
@Component
public class MatchCancelledDeadLetterConsumer {

    @KafkaListener(
            topics = KafkaTopics.MATCH_CANCELLED + "-dlt",
            groupId = "booking-service-match-cancelled-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("ALERT: match-cancelled event landed on dead-letter topic after exhausting retries — "
                        + "bookings for a cancelled match were NOT cancelled and any refunds they are owed "
                        + "were NOT requested: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
