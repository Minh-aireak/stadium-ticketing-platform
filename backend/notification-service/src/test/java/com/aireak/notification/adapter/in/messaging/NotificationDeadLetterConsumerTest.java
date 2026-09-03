package com.aireak.notification.adapter.in.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aireak.common.kafka.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * Unit test for {@link NotificationDeadLetterConsumer}: it never throws on a dead-lettered
 * record, and it only speaks for this service's own listeners.
 *
 * <p>{@code payment.payment.succeeded} is consumed by booking-service as well, and both services
 * republish their failures to the one {@code payment.payment.succeeded-dlt} topic. A
 * booking-service failure there means a booking is stuck, not that a customer email was lost —
 * the receipt this service sends from its own copy of that record went out fine.
 */
class NotificationDeadLetterConsumerTest {

    private final NotificationDeadLetterConsumer consumer = new NotificationDeadLetterConsumer();

    private ListAppender<ILoggingEvent> logged;
    private Logger consumerLogger;

    @BeforeEach
    void captureLogging() {
        logged = new ListAppender<>();
        logged.start();
        consumerLogger = (Logger) LoggerFactory.getLogger(NotificationDeadLetterConsumer.class);
        consumerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLogging() {
        consumerLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void doesNotThrowForADeadLetteredRecordWithUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.ACCOUNT_REGISTERED + "-dlt", 0, 0L, "account-1", "not valid json");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotClaimAnEmailWasLostWhenAnotherServicesListenerFailed() {
        consumer.onDeadLetter(deadLetteredBy("booking-service-payment"));

        assertThat(errorMessages()).isEmpty();
    }

    /** Guard: a failure in any of this service's three listener groups still alerts. */
    @Test
    void stillAlertsForEachOfThisServicesOwnConsumerGroups() {
        for (String ownGroup : List.of("notification-service-identity", "notification-service-booking",
                "notification-service-payment")) {
            logged.list.clear();

            consumer.onDeadLetter(deadLetteredBy(ownGroup));

            assertThat(errorMessages())
                    .as("a failure in %s is this service's own", ownGroup)
                    .singleElement(STRING).contains("ALERT:");
        }
    }

    /**
     * Guard: a record with no consumer-group header cannot be attributed to anyone, and going
     * quiet on it would be the one failure a dead-letter consumer exists to rule out.
     */
    @Test
    void stillAlertsWhenTheRecordCannotBeAttributed() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.PAYMENT_SUCCEEDED + "-dlt", 0, 3L, "booking-4", "{}");

        consumer.onDeadLetter(record);

        assertThat(errorMessages()).singleElement(STRING).contains("ALERT:");
    }

    /**
     * The alert rule that fires on a dead-lettered record tells the operator to "grep the logs
     * for ALERT: to find which one and why" (see infra/prometheus/rules/platform-alerts.yml). The
     * recoverer puts the why on the record; the line has to pass it on, or the grep ends at a
     * topic, a partition and an offset.
     */
    @Test
    void namesTheFailureThatDeadLetteredTheRecord() {
        ConsumerRecord<String, String> record = deadLetteredBy("notification-service-payment");
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "freemarker.template.TemplateException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "The following has evaluated to null: seatCodes".getBytes(StandardCharsets.UTF_8)));

        consumer.onDeadLetter(record);

        assertThat(errorMessages()).singleElement(STRING)
                .contains("freemarker.template.TemplateException")
                .contains("The following has evaluated to null: seatCodes");
    }

    private static ConsumerRecord<String, String> deadLetteredBy(String consumerGroup) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.PAYMENT_SUCCEEDED + "-dlt", 0, 1L, "booking-3", "{}");
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
                consumerGroup.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private List<String> errorMessages() {
        return logged.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
