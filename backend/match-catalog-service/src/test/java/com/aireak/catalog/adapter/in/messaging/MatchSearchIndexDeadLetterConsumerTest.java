package com.aireak.catalog.adapter.in.messaging;

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
 * Unit test for {@link MatchSearchIndexDeadLetterConsumer}.
 *
 * <p>The listener must survive whatever lands on the topic — a well-formed envelope the indexer
 * failed on, or raw bytes that never deserialized — and must report only the failures that are
 * the search indexer's: the three {@code -dlt} topics it watches are shared with booking-service
 * and ticket-inventory-service, whose own dead-letter consumers describe their own consequences.
 */
class MatchSearchIndexDeadLetterConsumerTest {

    private final MatchSearchIndexDeadLetterConsumer consumer = new MatchSearchIndexDeadLetterConsumer();

    private ListAppender<ILoggingEvent> logged;
    private Logger consumerLogger;

    @BeforeEach
    void captureLogging() {
        logged = new ListAppender<>();
        logged.start();
        consumerLogger = (Logger) LoggerFactory.getLogger(MatchSearchIndexDeadLetterConsumer.class);
        consumerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLogging() {
        consumerLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void doesNotThrowForADeadLetteredRecordWithAnUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_PUBLISHED + "-dlt", 0, 0L, "match-1", "not valid json");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void reportsTheSearchIndexersOwnFailureAtError() {
        consumer.onDeadLetter(deadLetteredBy(MatchSearchIndexConsumer.GROUP_ID));

        assertThat(errorMessages()).singleElement(STRING)
                .contains("ALERT:")
                .contains("search index no longer matches the database");
    }

    /**
     * booking-service consumes catalog.match.cancelled to cancel bookings; its failure on that
     * topic is a refund never issued, and its own dead-letter consumer says so. Calling it a
     * stale search document here would understate it — so this stays quiet at ERROR for it.
     */
    @Test
    void doesNotDescribeAnotherServicesFailureAsAStaleSearchIndex() {
        consumer.onDeadLetter(deadLetteredBy("booking-service-match-cancelled"));

        assertThat(errorMessages()).isEmpty();
    }

    /**
     * A record with no consumer-group header cannot be attributed to anyone, and going quiet on
     * it would be the one failure a dead-letter consumer exists to rule out.
     */
    @Test
    void stillReportsWhenTheRecordCannotBeAttributed() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_COMPLETED + "-dlt", 0, 3L, "match-4", "{}");

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
        ConsumerRecord<String, String> record = deadLetteredBy(MatchSearchIndexConsumer.GROUP_ID);
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "java.net.ConnectException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "Connection refused: elasticsearch/172.18.0.9:9200".getBytes(StandardCharsets.UTF_8)));

        consumer.onDeadLetter(record);

        assertThat(errorMessages()).singleElement(STRING)
                .contains("java.net.ConnectException")
                .contains("Connection refused: elasticsearch/172.18.0.9:9200");
    }

    private static ConsumerRecord<String, String> deadLetteredBy(String consumerGroup) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.MATCH_PUBLISHED + "-dlt", 0, 1L, "match-3", "{}");
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
