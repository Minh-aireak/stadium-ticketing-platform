package com.aireak.booking.config;

import com.aireak.common.event.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
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

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, EventEnvelope<?>> consumerFactory() {
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
        var factory = new ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<?>>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    /**
     * Plain String/String factory for {@code PaymentResultDeadLetterConsumer} — a record can land
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
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(deadLetterConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
