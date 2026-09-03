package com.aireak.inventory.adapter.in.messaging;

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
 * Unit test for {@link CatalogCacheDeadLetterConsumer}: it never throws on a dead-lettered
 * record, and it only speaks for this service's own listeners.
 *
 * <p>{@code catalog.match.cancelled} is consumed by booking-service as well, and both services
 * republish their failures to the one {@code catalog.match.cancelled-dlt} topic. A booking-service
 * failure there is a cancelled match whose paid bookings were never refunded; describing it as a
 * stale bookability cache understates it by everything that matters.
 */
class CatalogCacheDeadLetterConsumerTest {

    private final CatalogCacheDeadLetterConsumer consumer = new CatalogCacheDeadLetterConsumer();

    private ListAppender<ILoggingEvent> logged;
    private Logger consumerLogger;

    @BeforeEach
    void captureLogging() {
        logged = new ListAppender<>();
        logged.start();
        consumerLogger = (Logger) LoggerFactory.getLogger(CatalogCacheDeadLetterConsumer.class);
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
                KafkaTopics.MATCH_COMPLETED + "-dlt", 0, 0L, "match-1", "not valid json");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotDescribeAnotherServicesFailureAsAStaleCache() {
        consumer.onDeadLetter(deadLetteredBy("booking-service-match-cancelled"));

        assertThat(warnMessages()).isEmpty();
    }

    /** Guard: the failure this consumer does speak for still reports. */
    @Test
    void stillReportsThisServicesOwnFailure() {
        consumer.onDeadLetter(deadLetteredBy("ticket-inventory-service-catalog"));

        assertThat(warnMessages()).singleElement(STRING).contains("bookability cache");
    }

    /**
     * Guard: a record with no consumer-group header cannot be attributed to anyone, and going
     * quiet on it would be the one failure a dead-letter consumer exists to rule out.
     */
    @Test
    void stillReportsWhenTheRecordCannotBeAttributed() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_CANCELLED + "-dlt", 0, 3L, "match-4", "{}");

        consumer.onDeadLetter(record);

        assertThat(warnMessages()).singleElement(STRING).contains("bookability cache");
    }

    /**
     * The alert rule that fires on a dead-lettered record tells the operator to "grep the logs
     * for ALERT: to find which one and why" (see infra/prometheus/rules/platform-alerts.yml). The
     * recoverer puts the why on the record; the line has to pass it on, or the grep ends at a
     * topic, a partition and an offset.
     */
    @Test
    void namesTheFailureThatDeadLetteredTheRecord() {
        ConsumerRecord<String, String> record = deadLetteredBy("ticket-inventory-service-catalog");
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "org.redisson.client.RedisTimeoutException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "Command execution timeout for command: EVAL".getBytes(StandardCharsets.UTF_8)));

        consumer.onDeadLetter(record);

        assertThat(warnMessages()).singleElement(STRING)
                .contains("org.redisson.client.RedisTimeoutException")
                .contains("Command execution timeout for command: EVAL");
    }

    private static ConsumerRecord<String, String> deadLetteredBy(String consumerGroup) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_CANCELLED + "-dlt", 0, 1L, "match-3", "{}");
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
                consumerGroup.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private List<String> warnMessages() {
        return logged.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
