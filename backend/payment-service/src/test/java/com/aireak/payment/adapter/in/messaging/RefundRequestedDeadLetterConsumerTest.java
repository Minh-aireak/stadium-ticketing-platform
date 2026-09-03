package com.aireak.payment.adapter.in.messaging;

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
 * Unit test for {@link RefundRequestedDeadLetterConsumer}.
 *
 * <p>Confirms the DLT listener handles whatever lands on the topic without throwing — a record
 * arrives there either because {@link RefundRequestedConsumer} threw (the value is well-formed
 * envelope JSON) or because deserialization failed (the value is whatever raw bytes came in), and
 * a listener that threw on the second case would take down the one thing reporting that a refund
 * was lost.
 */
class RefundRequestedDeadLetterConsumerTest {

    private final RefundRequestedDeadLetterConsumer consumer = new RefundRequestedDeadLetterConsumer();

    private ListAppender<ILoggingEvent> logged;
    private Logger consumerLogger;

    @BeforeEach
    void captureLogging() {
        logged = new ListAppender<>();
        logged.start();
        consumerLogger = (Logger) LoggerFactory.getLogger(RefundRequestedDeadLetterConsumer.class);
        consumerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLogging() {
        consumerLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void doesNotThrowForADeadLetteredRefundRequest() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.REFUND_REQUESTED + "-dlt", 0, 0L, "booking-1",
                "{\"eventType\":\"booking.refund.requested\"}");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowForADeadLetteredRecordWithAnUnparsableValue() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.REFUND_REQUESTED + "-dlt", 0, 0L, "booking-2", "not valid json payload");

        assertThatCode(() -> consumer.onDeadLetter(record)).doesNotThrowAnyException();
    }

    /**
     * The alert rule that fires on a dead-lettered record tells the operator to "grep the logs
     * for ALERT: to find which one and why" (see infra/prometheus/rules/platform-alerts.yml). The
     * recoverer puts the why on the record; the line has to pass it on, or the grep ends at a
     * topic, a partition and an offset — and on this topic the difference between a declined
     * gateway call and a malformed payload is the difference between retrying by hand and not.
     */
    @Test
    void namesTheFailureThatDeadLetteredTheRecord() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.REFUND_REQUESTED + "-dlt", 0, 1L, "booking-3", "{}");
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                "com.stripe.exception.InvalidRequestException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "Charge ch_123 has already been refunded".getBytes(StandardCharsets.UTF_8)));

        consumer.onDeadLetter(record);

        assertThat(logged.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList())
                .singleElement(STRING)
                .contains("com.stripe.exception.InvalidRequestException")
                .contains("Charge ch_123 has already been refunded");
    }
}
