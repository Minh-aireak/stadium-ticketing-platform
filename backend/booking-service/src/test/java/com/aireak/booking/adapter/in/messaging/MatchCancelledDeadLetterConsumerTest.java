package com.aireak.booking.adapter.in.messaging;

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

/**
 * Unit test for {@link MatchCancelledDeadLetterConsumer}.
 *
 * <p>Confirms the DLT listener handles a record cleanly whether its value is valid JSON or the
 * raw bytes of a payload that never deserialized — the second case is why the dead-letter
 * container factory is String-typed at all (see {@code AbstractKafkaConsumerConfig}), and a
 * listener that threw on it would take down the one thing reporting the failure.
 *
 * <p>And that it only speaks for its own listener. {@code catalog.match.cancelled} is consumed by
 * two services, so its one {@code -dlt} topic carries both services' failures to both services'
 * dead-letter consumers.
 */
class MatchCancelledDeadLetterConsumerTest {

    private final MatchCancelledDeadLetterConsumer consumer = new MatchCancelledDeadLetterConsumer();

    private ListAppender<ILoggingEvent> logged;
    private Logger consumerLogger;

    @BeforeEach
    void captureLogging() {
        logged = new ListAppender<>();
        logged.start();
        consumerLogger = (Logger) LoggerFactory.getLogger(MatchCancelledDeadLetterConsumer.class);
        consumerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLogging() {
        consumerLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void doesNotThrowForDeadLetteredMatchCancelledRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_CANCELLED + "-dlt", 0, 0L, "match-1",
                "{\"eventType\":\"catalog.match.cancelled\"}");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowForDeadLetteredRecordWithUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_CANCELLED + "-dlt", 0, 0L, "match-2", "not valid json payload");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    /**
     * The record ticket-inventory-service dead-letters when its cache-warming listener fails. It
     * lands on the same topic as booking-service's own failures, and costs a redundant REST call
     * per hold — no booking is left uncancelled and no refund is left unrequested. Saying so sends
     * an operator hunting for money that was never at risk, at the platform's loudest level.
     */
    @Test
    void doesNotClaimBookingsWereMissedWhenAnotherServicesListenerFailed() {
        consumer.onDeadLetter(deadLetteredBy("ticket-inventory-service-catalog"));

        assertThat(errorMessages()).isEmpty();
    }

    /** Guard: the failure this consumer does speak for still alerts. */
    @Test
    void stillAlertsForThisServicesOwnFailure() {
        consumer.onDeadLetter(deadLetteredBy("booking-service-match-cancelled"));

        assertThat(errorMessages()).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("ALERT:");
    }

    /**
     * Guard: a record with no consumer-group header cannot be attributed to anyone, and going
     * quiet on it would be the one failure a dead-letter consumer exists to rule out.
     */
    @Test
    void stillAlertsWhenTheRecordCannotBeAttributed() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_CANCELLED + "-dlt", 0, 3L, "match-4", "{}");

        consumer.onDeadLetter(record);

        assertThat(errorMessages()).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("ALERT:");
    }

    /**
     * The alert rule that fires on a dead-lettered record tells the operator to "grep the logs
     * for ALERT: to find which one and why" (see infra/prometheus/rules/platform-alerts.yml). The
     * recoverer puts the why on the record; the line has to pass it on, or the grep ends at a
     * topic, a partition and an offset.
     */
    @Test
    void namesTheFailureThatDeadLetteredTheRecord() {
        ConsumerRecord<String, String> record = deadLetteredBy("booking-service-match-cancelled");
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "org.springframework.dao.CannotAcquireLockException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "could not obtain lock on row in relation bookings".getBytes(StandardCharsets.UTF_8)));

        consumer.onDeadLetter(record);

        assertThat(errorMessages()).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("org.springframework.dao.CannotAcquireLockException")
                .contains("could not obtain lock on row in relation bookings");
    }

    private static ConsumerRecord<String, String> deadLetteredBy(String consumerGroup) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_CANCELLED + "-dlt", 0, 1L, "match-3", "{}");
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
