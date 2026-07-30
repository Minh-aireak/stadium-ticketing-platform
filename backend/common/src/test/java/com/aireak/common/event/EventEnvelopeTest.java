package com.aireak.common.event;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the outbox payload wrapper every producer/consumer in the platform
 * depends on (see {@link EventEnvelope}'s own javadoc for the bug this guards against: without
 * the explicit {@code @JsonCreator}/{@code @JsonProperty} constructor, Jackson silently falls
 * back to a no-arg constructor on deserialization and every field except {@code payload} comes
 * back null).
 */
class EventEnvelopeTest {

    // Mirrors every service's KafkaConfig: EventEnvelope.payload erases to Object, and Jackson 3's
    // default PolymorphicTypeValidator refuses to resolve subtypes of a base type that generic.
    private static final PolymorphicTypeValidator POLYMORPHIC_TYPE_VALIDATOR = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Object.class)
            .allowIfSubType("com.aireak.")
            .build();

    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules(EventEnvelopeTest.class.getClassLoader())
            .polymorphicTypeValidator(POLYMORPHIC_TYPE_VALIDATOR)
            .build();

    @Test
    void of_withoutExplicitEventId_generatesARandomIdAndPopulatesEveryField() {
        TestPayload payload = new TestPayload("hello");

        EventEnvelope<TestPayload> envelope = EventEnvelope.of("test.topic", payload, "trace-1");

        assertThat(envelope.getEventId()).isNotBlank();
        assertThat(envelope.getEventType()).isEqualTo("test.topic");
        assertThat(envelope.getOccurredAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
        assertThat(envelope.getTraceId()).isEqualTo("trace-1");
        assertThat(envelope.getPayload()).isEqualTo(payload);
    }

    @Test
    void of_calledTwice_generatesDifferentEventIds() {
        TestPayload payload = new TestPayload("hello");

        EventEnvelope<TestPayload> first = EventEnvelope.of("test.topic", payload, null);
        EventEnvelope<TestPayload> second = EventEnvelope.of("test.topic", payload, null);

        assertThat(first.getEventId()).isNotEqualTo(second.getEventId());
    }

    @Test
    void of_withExplicitEventId_usesTheProvidedIdInstead() {
        EventEnvelope<TestPayload> envelope =
                EventEnvelope.of("fixed-event-id", "test.topic", new TestPayload("hello"), null);

        assertThat(envelope.getEventId()).isEqualTo("fixed-event-id");
        assertThat(envelope.getTraceId()).isNull();
    }

    @Test
    void jsonRoundTrip_preservesEveryFieldAndResolvesThePolymorphicPayloadType() {
        EventEnvelope<TestPayload> original =
                EventEnvelope.of("fixed-event-id", "test.topic", new TestPayload("hello"), "trace-1");

        String json = OBJECT_MAPPER.writeValueAsString(original);
        EventEnvelope<?> deserialized = OBJECT_MAPPER.readValue(json, EventEnvelope.class);

        // Every field other than payload is private with no setters and Jackson has no @JsonCreator
        // fallback if the annotation is ever dropped — a bare no-arg-constructor deserialization
        // would silently leave these null while payload alone (protected by its own
        // @JsonTypeInfo) survives, which is exactly the bug this test guards against.
        assertThat(deserialized.getEventId()).isEqualTo(original.getEventId());
        assertThat(deserialized.getEventType()).isEqualTo(original.getEventType());
        assertThat(deserialized.getOccurredAt()).isEqualTo(original.getOccurredAt());
        assertThat(deserialized.getTraceId()).isEqualTo(original.getTraceId());
        assertThat(deserialized.getPayload())
                .isInstanceOf(TestPayload.class)
                .isEqualTo(original.getPayload());
    }

    public record TestPayload(String value) {
    }
}
