package com.aireak.common.kafka;

import com.aireak.common.event.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.util.HashMap;
import java.util.Map;

/**
 * The Kafka consumer wiring that is the same in every service holding a {@code @KafkaListener}: an
 * {@link EventEnvelope} consumer factory, a retry-then-dead-letter error handler, and a
 * String-typed factory for the dead-letter listeners. A service extends this with an otherwise
 * empty {@code KafkaConfig} carrying {@code @Configuration} and {@code @EnableKafka}.
 *
 * <p>This class exists for the same reason {@code AbstractOutboxEventPublisher} does: "the same in
 * every service" meant five copies of 142-168 lines, and they had started to drift. The note on
 * why {@code @EnableKafka} is not optional survived in three files, the one on why the
 * deserializer needs its own {@link PolymorphicTypeValidator} in three, the one on what the error
 * handler replaces in one. Each documents a failure that is silent when you get it wrong, so a
 * note missing from most of the copies is how the remaining one eventually gets "tidied" to match.
 *
 * <p><strong>{@code @EnableKafka} stays on the subclass</strong>, and is not optional there.
 * Declaring {@code kafkaListenerContainerFactory} as a bean satisfies Spring Boot's
 * {@code @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")} guard on its own
 * autoconfigured {@code @EnableKafka}, which switches that off — silently leaving every
 * {@code @KafkaListener} method unregistered, with no consumer group, no subscription and no
 * error, unless the service re-enables it.
 *
 * <p>Producing is deliberately absent apart from {@link #deadLetterKafkaTemplate()}. Domain events
 * ride the Postgres outbox and Debezium (see {@code AbstractOutboxEventPublisher}) so a crash
 * between the database commit and the send cannot lose them. A dead-letter republish carries no
 * such coupling: there is no transaction to stay consistent with, only a record that has already
 * failed everything else and needs somewhere to go other than {@code /dev/null}.
 */
public abstract class AbstractKafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Explicit target type, not {@code new JacksonJsonDeserializer<>()}: the no-arg form has no
     * default type and falls back to reading the {@code __TypeId__} header — but these topics are
     * populated by Debezium's outbox EventRouter (see {@code infra/debezium/*.json}), which
     * forwards the outbox payload column as-is and never sets that header. Without a default type
     * every real record throws {@code SerializationException("No type information in headers and
     * no default type provided")} and ends up on the dead-letter topic.
     *
     * <p>{@code EventEnvelope.payload} is generic (it erases to Object) and carries its own
     * {@code @JsonTypeInfo(use = Id.CLASS)} for polymorphic resolution — but Jackson 3's default
     * {@link PolymorphicTypeValidator} unconditionally refuses to resolve subtypes of a declared
     * base type that generic ({@code InvalidDefinitionException: "too generic base type can open a
     * security hole"}), regardless of {@code addTrustedPackages()} below: that governs only
     * spring-kafka's OWN header-based type mapper, not Jackson's internal one. The JsonMapper
     * needs its own validator, scoped to the same trusted package tree, or every payload fails.
     */
    @Bean
    public ConsumerFactory<String, EventEnvelope<?>> consumerFactory() {
        PolymorphicTypeValidator polymorphicTypeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("com.aireak.")
                .build();
        JsonMapper jsonMapper = JsonMapper.builder()
                .findAndAddModules(AbstractKafkaConsumerConfig.class.getClassLoader())
                .polymorphicTypeValidator(polymorphicTypeValidator)
                .build();
        JacksonJsonDeserializer<EventEnvelope<?>> deserializer =
                new JacksonJsonDeserializer<>(EventEnvelope.class, jsonMapper);
        deserializer.addTrustedPackages("com.aireak.*");

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    /** The only producer these services have; see this class's javadoc for why it is the only one. */
    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    /**
     * Without this bean the container falls back to Spring Kafka's default error handler, which is
     * {@code FixedBackOff(0, 9)}: ten attempts with no pause at all, then log the failure and seek
     * past the record. A projection update could therefore burn every retry inside a few
     * milliseconds — far too fast for the Postgres blip or the Redis reconnect that caused it to
     * have cleared — and the event would be dropped with nothing but a log line behind it. Backing
     * off to a 30-second ceiling and then republishing to {@code <topic>-dlt} keeps the record
     * instead, for a dead-letter consumer to alert on.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> deadLetterKafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate);
        var backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxInterval(10_000L);
        backOff.setMaxElapsedTime(30_000L);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<?>> kafkaListenerContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<?>>();
        factory.setConsumerFactory(consumerFactory());
        // RECORD: commit the offset after each listener call returns normally.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Tracks the partition count: every topic has 1 partition (docker-compose sets no
        // KAFKA_NUM_PARTITIONS), and Kafka never hands one partition to two consumers in the
        // same group, so anything higher only adds idle consumers. Raise this in lockstep with
        // the partition count, never on its own.
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        // Restores the publishing request's correlation id from EventEnvelope.traceId into the MDC
        // for the duration of each record, so a consumer's log lines carry the same correlationId
        // as the HTTP request that originally triggered the event. Without it every asynchronous
        // hop is a hole in the trace.
        factory.setRecordInterceptor(new CorrelationIdRecordInterceptor());
        return factory;
    }

    /**
     * Plain String/String factory for the dead-letter listeners. A record lands on a {@code -dlt}
     * topic either because the listener threw (the payload deserialized fine as an
     * {@link EventEnvelope}) or because deserialization itself failed (the payload is whatever raw
     * bytes arrived, and is not valid EventEnvelope JSON). Reusing {@link #consumerFactory()}'s
     * deserializer would throw on the second case and, with no error handler attached here, kill
     * the dead-letter listener container outright — so the one listener whose whole job is to
     * report that a message died for good would itself die, silently. A plain String can never
     * fail to deserialize the alert.
     */
    @Bean
    public ConsumerFactory<String, String> deadLetterConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> deadLetterKafkaListenerContainerFactory(
            ConsumerFactory<String, String> deadLetterConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(deadLetterConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Reads the correlation id straight out of the raw JSON, since this factory is String-typed
        // on purpose: the line a dead-letter listener logs is the one that says a message died for
        // good, and the one most worth tracing back to the request that produced it.
        factory.setRecordInterceptor(new DeadLetterCorrelationIdRecordInterceptor());
        return factory;
    }
}
