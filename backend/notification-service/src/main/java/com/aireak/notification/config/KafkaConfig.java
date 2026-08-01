package com.aireak.notification.config;

import com.aireak.common.event.EventEnvelope;
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
 * Kafka consumer configuration for notification-service.
 *
 * <p>Key decisions:
 * <ul>
 *   <li>RECORD ack mode — commits offset after each successful record processing.</li>
 *   <li>Deserializes messages as {@link EventEnvelope} via JacksonJsonDeserializer (Jackson 3).</li>
 *   <li>Trusted packages set to {@code com.aireak.*} for safe polymorphic deserialization.</li>
 *   <li>{@code kafkaErrorHandler} (same shape as booking-service's): retries a failing record
 *       with exponential backoff, then republishes it to a {@code <topic>.DLT} topic instead of
 *       either looping forever or silently dropping it — see {@code EmailSenderPort}/
 *       {@code SmsSenderPort} failures in {@code NotificationDispatchService#dispatch}, which
 *       propagate out of the {@code @KafkaListener} method and previously hit Spring Kafka's
 *       default handler (log-and-skip after a fixed retry count, no DLQ trail).</li>
 * </ul>
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
        // JacksonJsonDeserializer (Jackson 3) — replaces deprecated JsonDeserializer.
        // Explicit target type, not new JacksonJsonDeserializer<>(): the no-arg form has no
        // default type and falls back to reading the __TypeId__ header — but these topics are
        // populated by Debezium's outbox EventRouter (see infra/debezium/*.json), which forwards
        // the outbox payload column as-is and never sets that header. Without a default type,
        // every real record throws SerializationException("No type information in headers and
        // no default type provided") and ends up on the DLT.
        //
        // EventEnvelope.payload is generic (erases to Object) and carries its own
        // @JsonTypeInfo(use = Id.CLASS) for polymorphic resolution — but Jackson 3's default
        // PolymorphicTypeValidator unconditionally refuses to resolve subtypes of a declared base
        // type that generic (InvalidDefinitionException: "too generic base type can open a
        // security hole"), regardless of addTrustedPackages() below (that only governs spring-kafka's
        // OWN header-based type mapper, not Jackson's internal one). The JsonMapper here needs its
        // own validator, scoped to the same trusted package tree, or every payload fails to parse.
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
        // RECORD: commit offset per-record after listener returns normally
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
