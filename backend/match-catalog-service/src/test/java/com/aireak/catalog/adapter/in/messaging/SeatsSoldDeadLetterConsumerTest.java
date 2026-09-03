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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * Unit test for {@link SeatsSoldDeadLetterConsumer}.
 *
 * <p>Confirms the DLT listener handles whatever lands on the topic without throwing. Both shapes
 * matter: a record can arrive there because the projection failed (the value is well-formed
 * envelope JSON) or because deserialization itself failed (the value is whatever raw bytes came
 * in). A listener that threw on the second case would take its own container down — and the alert
 * with it.
 */
class SeatsSoldDeadLetterConsumerTest {

    private final SeatsSoldDeadLetterConsumer consumer = new SeatsSoldDeadLetterConsumer();

    private ListAppender<ILoggingEvent> logged;
    private Logger consumerLogger;

    @BeforeEach
    void captureLogging() {
        logged = new ListAppender<>();
        logged.start();
        consumerLogger = (Logger) LoggerFactory.getLogger(SeatsSoldDeadLetterConsumer.class);
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
                KafkaTopics.SEATS_SOLD + "-dlt", 0, 1L, "showtime-3", "{}");
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "jakarta.persistence.EntityNotFoundException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "Showtime not found: showtime-3".getBytes(StandardCharsets.UTF_8)));

        consumer.onDeadLetter(record);

        assertThat(logged.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList())
                .singleElement(STRING)
                .contains("jakarta.persistence.EntityNotFoundException")
                .contains("Showtime not found: showtime-3");
    }

    @Test
    void doesNotThrowForADeadLetteredSoldSeatRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SEATS_SOLD + "-dlt", 0, 0L, "showtime-1",
                "{\"eventType\":\"inventory.seats.sold\"}");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowForADeadLetteredRecordWithAnUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.SEATS_SOLD + "-dlt", 0, 0L, "showtime-2", "not valid json payload");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }
}
