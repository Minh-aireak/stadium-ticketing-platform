package com.aireak.inventory.config;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.CorrelationIdRecordInterceptor;
import com.aireak.common.kafka.DeadLetterCorrelationIdRecordInterceptor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
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
 * Kafka consumer configuration for ticket-inventory-service (first real consumer this service
 * has — everything else here is producer-only via the outbox, see {@code OutboxEventPublisher}).
 *
 * <p>Same shape as notification-service/booking-service's {@code KafkaConfig} — see that
 * javadoc for why each piece is needed (explicit {@code EventEnvelope} target type instead of
 * the no-arg deserializer form, a scoped {@code PolymorphicTypeValidator} for {@code
 * EventEnvelope.payload}'s {@code @JsonTypeInfo(use = Id.CLASS)}, retry-then-DLT error handling).
 *
 * <p>{@code @EnableKafka} is required here: defining {@code kafkaListenerContainerFactory} as a
 * bean below satisfies Spring Boot's {@code @ConditionalOnMissingBean(name =
 * "kafkaListenerContainerFactory")} guard on its own autoconfigured {@code @EnableKafka}, which
 * disables it — silently leaving every {@code @KafkaListener} method unregistered (no consumer
 * group, no subscription, no error) unless this class re-enables it explicitly.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, EventEnvelope<?>> consumerFactory() {
        PolymorphicTypeValidator polymorphicTypeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("com.aireak.")
                .build();
        JsonMapper jsonMapper = JsonMapper.builder()
                .findAndAddModules(KafkaConfig.class.getClassLoader())
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

    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

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
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<?>> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Tracks the partition count: every topic has 1 partition (docker-compose sets no
        // KAFKA_NUM_PARTITIONS), and Kafka never hands one partition to two consumers in the
        // same group, so anything higher only adds idle consumers. Raise this in lockstep with
        // the partition count, never on its own.
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        // Restores the publishing request's correlation ID from EventEnvelope.traceId into the MDC
        // for the duration of each record, so the consumers below log under the same ID as the HTTP
        // request that caused the event. Without it this service is the one asynchronous hop with
        // no correlation ID at all — exactly the catalog -> inventory seat-generation path.
        factory.setRecordInterceptor(new CorrelationIdRecordInterceptor());
        return factory;
    }

    /**
     * Plain String/String factory for {@code ShowtimeAddedDeadLetterConsumer} — a record can land
     * on the {@code -dlt} topic either because the listener threw (payload deserialized fine as an
     * {@link EventEnvelope}) or because deserialization itself failed (payload is whatever raw
     * bytes came in, not valid EventEnvelope JSON). Reusing {@link #consumerFactory()}'s
     * EventEnvelope deserializer would throw on the second case and, with no error handler
     * attached here, kill the DLT listener container outright — a plain String avoids ever
     * failing to deserialize the alert itself.
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
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(deadLetterConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Reads the correlation ID straight out of the raw JSON, since this factory is String-typed
        // on purpose: the line a dead-letter listener logs is the one that says a message died for
        // good, and the one most worth tracing back to the request that produced it.
        factory.setRecordInterceptor(new DeadLetterCorrelationIdRecordInterceptor());
        return factory;
    }
}
