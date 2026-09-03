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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * Unit test for {@link ShowtimeAddedDeadLetterConsumer}.
 *
 * <p>Confirms that the DLT listener method handles incoming DLT records cleanly without throwing,
 * whether the record is valid JSON or unparsable raw text.
 */
class ShowtimeAddedDeadLetterConsumerTest {

    private final ShowtimeAddedDeadLetterConsumer consumer = new ShowtimeAddedDeadLetterConsumer();

    private ListAppender<ILoggingEvent> logged;
    private Logger consumerLogger;

    @BeforeEach
    void captureLogging() {
        logged = new ListAppender<>();
        logged.start();
        consumerLogger = (Logger) LoggerFactory.getLogger(ShowtimeAddedDeadLetterConsumer.class);
        consumerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLogging() {
        consumerLogger.detachAppender(logged);
        logged.stop();
    }

    /**
     * The alert rule that fires on a dead-lettered record tells the operator to "grep the logs
     * for ALERT: to find which one and why" (see infra/prometheus/rules/platform-alerts.yml). The
     * recoverer puts the why on the record; the line has to pass it on, or the grep ends at a
     * topic, a partition and an offset.
     */
    @Test
    void namesTheFailureThatDeadLetteredTheRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SHOWTIME_CREATED + "-dlt", 0, 1L, "showtime-3", "{}");
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "com.aireak.inventory.domain.exception.UnknownStadiumLayoutException"
                        .getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "No layout registered for stadium: my-dinh-2".getBytes(StandardCharsets.UTF_8)));

        consumer.onDeadLetter(record);

        assertThat(logged.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList())
                .singleElement(STRING)
                .contains("com.aireak.inventory.domain.exception.UnknownStadiumLayoutException")
                .contains("No layout registered for stadium: my-dinh-2");
    }

    @Test
    void doesNotThrowForDeadLetteredShowtimeCreatedRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SHOWTIME_CREATED + "-dlt", 0, 0L, "showtime-1", "{\"eventType\":\"catalog.showtime.created\"}");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowForDeadLetteredRecordWithUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SHOWTIME_CREATED + "-dlt", 0, 0L, "showtime-2", "not valid json payload");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }
}
