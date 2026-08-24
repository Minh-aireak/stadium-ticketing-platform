package com.aireak.catalog.config;

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

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, EventEnvelope<?>> consumerFactory() {
        PolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("com.aireak.")
                .build();
        JsonMapper jsonMapper = JsonMapper.builder()
                .findAndAddModules(KafkaConfig.class.getClassLoader())
                .polymorphicTypeValidator(validator)
                .build();
        JacksonJsonDeserializer<EventEnvelope<?>> deserializer =
                new JacksonJsonDeserializer<>(EventEnvelope.class, jsonMapper);
        deserializer.addTrustedPackages("com.aireak.*");

        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), deserializer);
    }

    /**
     * The one Kafka producer this service has. Domain events do NOT go through it — they ride the
     * Postgres outbox and Debezium (see {@code adapter.out.persistence.outbox}) so a crash between
     * the DB commit and the send cannot lose them. A dead-letter republish carries no such coupling:
     * there is no transaction to be consistent with, only a record that has already failed
     * everything else and needs somewhere to go besides {@code /dev/null}.
     */
    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
    }

    /**
     * Without this bean the container falls back to Spring Kafka's default error handler, which is
     * {@code FixedBackOff(0, 9)}: ten attempts with no pause at all, then log the failure and seek
     * past the record. For {@code SeatsSoldEventConsumer} that meant a sold-seat projection could
     * exhaust every retry inside a few milliseconds — far too fast for the Postgres blip or the
     * Redis reconnect that caused it to have resolved — and then be dropped, leaving
     * {@code available_seats} permanently short by that sale with no record of which one.
     *
     * <p>Same shape as booking-service's: back off exponentially from 500ms up to 10s, give up
     * after 30s of elapsed retrying, and republish to {@code <topic>-dlt} rather than discarding.
     * {@code SeatsSoldDeadLetterConsumer} turns whatever lands there into an alert.
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
        // Puts the event's traceId into the MDC for the duration of each record, so the
        // consumer's log lines carry the same correlationId as the HTTP request that
        // originally triggered the event.
        factory.setRecordInterceptor(new CorrelationIdRecordInterceptor());

        return factory;
    }

    /**
     * Plain String/String factory for {@code SeatsSoldDeadLetterConsumer} — a record can land on the
     * {@code -dlt} topic either because the listener threw (payload deserialized fine as an
     * {@link EventEnvelope}) or because deserialization itself failed (payload is whatever raw bytes
     * came in). Reusing {@link #consumerFactory()} would throw on the second case and, with no error
     * handler attached here, kill the DLT listener container outright — a plain String never fails
     * to deserialize the alert itself.
     */
    @Bean
    public ConsumerFactory<String, String> deadLetterConsumerFactory() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), new StringDeserializer());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> deadLetterKafkaListenerContainerFactory(
            ConsumerFactory<String, String> deadLetterConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(deadLetterConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Reads the correlation ID straight out of the raw JSON, since this factory is String-typed
        // on purpose: the line a dead-letter listener logs is the one that says a message died for
        // good, and the one most worth tracing back to the request that produced it.
        factory.setRecordInterceptor(new DeadLetterCorrelationIdRecordInterceptor());
        return factory;
    }
}
