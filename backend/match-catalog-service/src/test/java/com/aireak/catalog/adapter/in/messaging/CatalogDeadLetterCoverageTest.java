package com.aireak.catalog.adapter.in.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
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
 * ERROR line. Here that line is what stands between a lost {@code SEATS_SOLD} record and a
 * showtime whose {@code available_seats} is permanently short by that sale, with nothing else in
 * the read side able to notice the projection and the inventory have diverged.
 *
 * <p>The consumers are found by scanning this package for {@code @KafkaListener} methods, not by
 * a list kept here. A hand-kept list is the same invisible gap one level up: a consumer added
 * without being listed is watched by nothing and pins nothing, and the test still passes. The
 * scan is what makes "a new consumer" — not just a new topic on an existing one — fail here
 * without this file being touched.
 */
class CatalogDeadLetterCoverageTest {

    private static final String DLT_SUFFIX = "-dlt";
    private static final String CONSUMER_PACKAGE = CatalogDeadLetterCoverageTest.class.getPackageName();

    @Test
    void everyConsumedTopicHasItsDeadLetterTopicWatched() {
        Set<String> expected = topicsOf(eventConsumers()).stream()
                .map(topic -> topic + DLT_SUFFIX)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(topicsOf(deadLetterConsumers()))
                .as("every topic this service consumes must have its -dlt topic watched, or a "
                        + "dead-lettered record is reported by nothing")
                .containsAll(expected);
    }

    /** Guards the other direction: a stale entry for a topic no consumer reads any more. */
    @Test
    void watchesNoDeadLetterTopicForAnEventNobodyConsumes() {
        Set<String> consumed = topicsOf(eventConsumers());

        assertThat(topicsOf(deadLetterConsumers()))
                .allSatisfy(dltTopic -> {
                    assertThat(dltTopic).endsWith(DLT_SUFFIX);
                    assertThat(consumed)
                            .as("%s is watched but its source topic is no longer consumed here", dltTopic)
                            .contains(dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length()));
                });
    }

    /**
     * Fails loudly if the scan comes back empty, which would make everything above vacuously
     * true — the failure mode a package rename or a moved consumer would otherwise cause silently.
     */
    @Test
    void theScanFindsBothHalvesOfThisPackage() {
        assertThat(eventConsumers())
                .as("no @KafkaListener event consumer found in %s", CONSUMER_PACKAGE)
                .isNotEmpty();
        assertThat(deadLetterConsumers())
                .as("no @KafkaListener dead-letter consumer found in %s", CONSUMER_PACKAGE)
                .isNotEmpty();
    }

    /**
     * A consumer reading live and {@code -dlt} topics from one method would fall out of both
     * halves above and be checked by neither — the same silent hole in a new shape.
     */
    @Test
    void noConsumerMixesLiveAndDeadLetterTopics() {
        for (Class<?> consumer : kafkaListenerClasses()) {
            Set<String> topics = topicsOf(List.of(consumer));
            assertThat(isDeadLetterConsumer(consumer) || isEventConsumer(consumer))
                    .as("%s mixes live and dead-letter topics %s, so neither half of this test "
                            + "covers it", consumer.getSimpleName(), topics)
                    .isTrue();
        }
    }

    /**
     * Every class in this package declaring at least one {@code @KafkaListener} method, whether
     * or not anyone remembered it existed.
     */
    private static List<Class<?>> kafkaListenerClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
        return scanner.findCandidateComponents(CONSUMER_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .map(CatalogDeadLetterCoverageTest::load)
                .filter(type -> !topicsOf(List.of(type)).isEmpty())
                .toList();
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Scanned class is not loadable: " + className, e);
        }
    }

    private static boolean isDeadLetterConsumer(Class<?> consumer) {
        return topicsOf(List.of(consumer)).stream().allMatch(topic -> topic.endsWith(DLT_SUFFIX));
    }

    private static boolean isEventConsumer(Class<?> consumer) {
        return topicsOf(List.of(consumer)).stream().noneMatch(topic -> topic.endsWith(DLT_SUFFIX));
    }

    /** Consumers that read real events, as opposed to alerting on dead ones. */
    private static List<Class<?>> eventConsumers() {
        return kafkaListenerClasses().stream().filter(CatalogDeadLetterCoverageTest::isEventConsumer).toList();
    }

    private static List<Class<?>> deadLetterConsumers() {
        return kafkaListenerClasses().stream().filter(CatalogDeadLetterCoverageTest::isDeadLetterConsumer).toList();
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
