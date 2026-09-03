package com.aireak.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Reads the headers {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}
 * stamps on a record when it republishes it to a {@code -dlt} topic.
 *
 * <p>Exists because a {@code -dlt} topic belongs to a topic, not to a service. Two of this
 * platform's topics are consumed by two services each — {@code catalog.match.cancelled} by
 * booking-service and ticket-inventory-service, {@code payment.payment.succeeded} by
 * booking-service and notification-service — and every service publishes its failures to the same
 * {@code <topic>-dlt}. Both dead-letter consumers therefore receive both services' failures, and
 * a dead-letter consumer's whole output is one sentence saying what was lost. Without the
 * attribution below, half of those sentences are about a listener that never ran here.
 *
 * <p>{@link KafkaHeaders#DLT_ORIGINAL_CONSUMER_GROUP} is what separates them: the recoverer takes
 * it from {@code ListenerExecutionFailedException#getGroupId()}, so it names the consumer group
 * whose listener threw, and each service's group ids are its own.
 */
public final class DeadLetterRecords {

    /** What {@link #failureCause} returns for a record carrying no exception header at all. */
    public static final String UNKNOWN_CAUSE = "unknown (no dead-letter exception header on the record)";

    private DeadLetterRecords() {
        // utility class -- no instantiation
    }

    /**
     * The consumer group whose listener failed, or {@code null} when the record carries no such
     * header.
     */
    public static String originalConsumerGroup(ConsumerRecord<?, ?> record) {
        return header(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
    }

    /**
     * Whether the caller should report this record as its own failure.
     *
     * <p>True for a record dead-lettered by one of {@code consumerGroups}, and also true for one
     * that names no group at all: an unattributable record has to be reported by whoever sees it,
     * because the alternative is every dead-letter consumer staying quiet about it, which is the
     * one outcome this class of listener exists to rule out.
     */
    public static boolean deadLetteredBy(ConsumerRecord<?, ?> record, Set<String> consumerGroups) {
        String consumerGroup = originalConsumerGroup(record);
        return consumerGroup == null || consumerGroups.contains(consumerGroup);
    }

    /**
     * The failure that dead-lettered the record, as {@code <exception class>: <message>}.
     *
     * <p>Prefers the cause over the wrapper: the wrapper is always
     * {@code ListenerExecutionFailedException} and says nothing, while the cause is the exception
     * the listener actually threw. The message header carries the wrapper's message, which
     * spring-kafka builds by appending the cause's own message to it.
     */
    public static String failureCause(ConsumerRecord<?, ?> record) {
        String type = header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        if (type == null) {
            type = header(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
        }
        String message = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        if (type == null && message == null) {
            return UNKNOWN_CAUSE;
        }
        if (message == null) {
            return type;
        }
        return type == null ? message : type + ": " + message;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
