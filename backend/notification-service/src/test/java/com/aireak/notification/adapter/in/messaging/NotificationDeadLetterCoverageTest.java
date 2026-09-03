package com.aireak.notification.adapter.in.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@link NotificationDeadLetterConsumer} to the promise its javadoc makes: every topic this
 * service consumes has its {@code -dlt} counterpart watched.
 *
 * <p>Worth a test rather than a review habit because getting it wrong is invisible. A topic left
 * off that list still consumes, still retries, still dead-letters — the only thing missing is the
 * ERROR line saying a customer's email died for good, and nothing else in the system reports it.
 * Both gaps this test was written for arose that way: {@code BOOKING_CREATED} was never listed,
 * and {@code PAYMENT_REFUNDED} was added to {@link PaymentEventConsumer} without the DLT side.
 *
 * <p>Reads the {@code @KafkaListener} annotations directly instead of restating the topic names,
 * so a new consumer or a new topic on an existing one is caught without this test being touched.
 */
class NotificationDeadLetterCoverageTest {

    private static final String DLT_SUFFIX = "-dlt";

    /** Every consumer in this package that reads real events, as opposed to alerting on dead ones. */
    private static final List<Class<?>> EVENT_CONSUMERS =
            List.of(AccountEventConsumer.class, BookingEventConsumer.class, PaymentEventConsumer.class);

    @Test
    void everyConsumedTopicHasItsDeadLetterTopicWatched() {
        Set<String> expected = topicsOf(EVENT_CONSUMERS).stream()
                .map(topic -> topic + DLT_SUFFIX)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(topicsOf(List.of(NotificationDeadLetterConsumer.class)))
                .as("NotificationDeadLetterConsumer must watch the -dlt topic of every topic this "
                        + "service consumes, or a dead-lettered notification is reported by nothing")
                .containsAll(expected);
    }

    /** Guards the other direction: a stale entry for a topic no consumer reads any more. */
    @Test
    void watchesNoDeadLetterTopicForAnEventNobodyConsumes() {
        Set<String> consumed = topicsOf(EVENT_CONSUMERS);

        assertThat(topicsOf(List.of(NotificationDeadLetterConsumer.class)))
                .allSatisfy(dltTopic -> {
                    assertThat(dltTopic).endsWith(DLT_SUFFIX);
                    assertThat(consumed)
                            .as("%s is watched but its source topic is no longer consumed here", dltTopic)
                            .contains(dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length()));
                });
    }

    private static Set<String> topicsOf(List<Class<?>> consumers) {
        return consumers.stream()
                .flatMap(consumer -> Arrays.stream(consumer.getDeclaredMethods()))
                .map(method -> method.getAnnotation(KafkaListener.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(listener -> Arrays.stream(listener.topics()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Fails loudly if a consumer stops declaring topics at all, which would make the above vacuous. */
    @Test
    void theConsumersUnderTestActuallyDeclareTopics() {
        for (Class<?> consumer : EVENT_CONSUMERS) {
            assertThat(topicsOf(List.of(consumer)))
                    .as("%s declares no @KafkaListener topics", consumer.getSimpleName())
                    .isNotEmpty();
        }
        assertThat(hasKafkaListener(NotificationDeadLetterConsumer.class)).isTrue();
    }

    private static boolean hasKafkaListener(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map((Method method) -> method.getAnnotation(KafkaListener.class))
                .anyMatch(java.util.Objects::nonNull);
    }
}
