package com.aireak.notification.adapter.in.messaging;

import com.aireak.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: alerts on records that the notification consumers could not process even
 * after {@code KafkaConfig#kafkaErrorHandler}'s retries, and that were republished to the
 * {@code -dlt} topic.
 *
 * <p>Without this, a dead-lettered record was invisible: notification-service was the only service
 * that published to a DLT and then had nobody reading it (booking-service and
 * ticket-inventory-service both alert on theirs). A record here means a customer never got an
 * email they were owed — a verification link they cannot register without, a reset link they
 * asked for, or a receipt — and no other mechanism will send it, because
 * {@code NotificationDispatchService} is the only producer of these messages.
 *
 * <p>Note this is specifically <em>not</em> about email delivery: a provider outage or a rejected
 * address is logged and swallowed by {@code TransactionalEmailService} and never reaches the
 * error handler. A record on a DLT is a processing fault — a malformed payload, or the
 * notifications insert failing — so it needs a human, not a retry.
 *
 * <p><strong>Log + alert only, no auto-retry</strong>, for the same reason booking-service's
 * dead-letter consumer takes that stance: a poison-pill payload replays identically forever, and
 * re-running dispatch here would re-send any email that did go out before the failure.
 *
 * <p>The topic list below must name the {@code -dlt} counterpart of EVERY topic the three
 * consumers in this package subscribe to — a topic missing from it is a customer email that
 * vanishes exactly as silently as before this class existed, which is the one failure it was
 * written to rule out. Two had already gone missing that way: {@code BOOKING_CREATED} from the
 * start, and {@code PAYMENT_REFUNDED} the moment the refund email was added to
 * {@code PaymentEventConsumer}. {@code NotificationDeadLetterCoverageTest} now compares the two
 * sides so the next addition cannot repeat it.
 */
@Slf4j
@Component
public class NotificationDeadLetterConsumer {

    @KafkaListener(
            topics = {
                    KafkaTopics.ACCOUNT_REGISTERED + "-dlt",
                    KafkaTopics.ACCOUNT_ACTIVATED + "-dlt",
                    KafkaTopics.PASSWORD_RESET_REQUESTED + "-dlt",
                    KafkaTopics.PAYMENT_SUCCEEDED + "-dlt",
                    KafkaTopics.PAYMENT_REFUNDED + "-dlt",
                    KafkaTopics.BOOKING_CREATED + "-dlt",
                    KafkaTopics.BOOKING_CONFIRMED + "-dlt",
                    KafkaTopics.BOOKING_CANCELLED + "-dlt"
            },
            groupId = "notification-service-dlt",
            containerFactory = "deadLetterKafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("ALERT: notification event landed on dead-letter topic after exhausting retries — "
                        + "a customer email was never sent and nothing will retry it: "
                        + "topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
