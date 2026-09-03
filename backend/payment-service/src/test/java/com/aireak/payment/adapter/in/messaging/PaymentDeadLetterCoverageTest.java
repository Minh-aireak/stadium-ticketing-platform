package com.aireak.payment.adapter.in.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds this service's dead-letter consumers to the rule the platform's other services already
 * follow: every topic consumed here has its {@code -dlt} counterpart watched by something.
 *
 * <p>Worth a test rather than a review habit because getting it wrong is invisible. A topic left
 * uncovered still consumes, still retries, still dead-letters — the only thing missing is the
 * ERROR line, and this service consumes the one topic where that line is the last thing standing:
 * a dead-lettered {@code REFUND_REQUESTED} is a customer owed money that no reconciler will find,
 * because {@code UnreconciledPaymentAlertJob} only reports refunds that were issued and failed to
 * persist, not refunds that were never attempted.
 *
 * <p>Reads the {@code @KafkaListener} annotations directly instead of restating the topic names,
 * so a new consumer or a new topic on an existing one is caught without this test being touched.
 * Note the Stripe webhook enters through {@code StripeWebhookController} over HTTP, not Kafka, so
 * it is out of scope here by construction rather than by omission.
 */
class PaymentDeadLetterCoverageTest {

    private static final String DLT_SUFFIX = "-dlt";

    /** Every consumer in this package that reads real events, as opposed to alerting on dead ones. */
    private static final List<Class<?>> EVENT_CONSUMERS = List.of(RefundRequestedConsumer.class);

    private static final List<Class<?>> DEAD_LETTER_CONSUMERS = List.of(RefundRequestedDeadLetterConsumer.class);

    @Test
    void everyConsumedTopicHasItsDeadLetterTopicWatched() {
        Set<String> expected = topicsOf(EVENT_CONSUMERS).stream()
                .map(topic -> topic + DLT_SUFFIX)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(topicsOf(DEAD_LETTER_CONSUMERS))
                .as("every topic this service consumes must have its -dlt topic watched, or a "
                        + "dead-lettered record is reported by nothing")
                .containsAll(expected);
    }

    /** Guards the other direction: a stale entry for a topic no consumer reads any more. */
    @Test
    void watchesNoDeadLetterTopicForAnEventNobodyConsumes() {
        Set<String> consumed = topicsOf(EVENT_CONSUMERS);

        assertThat(topicsOf(DEAD_LETTER_CONSUMERS))
                .allSatisfy(dltTopic -> {
                    assertThat(dltTopic).endsWith(DLT_SUFFIX);
                    assertThat(consumed)
                            .as("%s is watched but its source topic is no longer consumed here", dltTopic)
                            .contains(dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length()));
                });
    }

    /** Fails loudly if a consumer stops declaring topics at all, which would make the above vacuous. */
    @Test
    void theConsumersUnderTestActuallyDeclareTopics() {
        for (Class<?> consumer : EVENT_CONSUMERS) {
            assertThat(topicsOf(List.of(consumer)))
                    .as("%s declares no @KafkaListener topics", consumer.getSimpleName())
                    .isNotEmpty();
        }
        for (Class<?> consumer : DEAD_LETTER_CONSUMERS) {
            assertThat(topicsOf(List.of(consumer)))
                    .as("%s declares no @KafkaListener topics", consumer.getSimpleName())
                    .isNotEmpty();
        }
    }

    private static Set<String> topicsOf(List<Class<?>> consumers) {
        return consumers.stream()
                .flatMap(consumer -> Arrays.stream(consumer.getDeclaredMethods()))
                .map(method -> method.getAnnotation(KafkaListener.class))
                .filter(Objects::nonNull)
                .flatMap(listener -> Arrays.stream(listener.topics()))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
