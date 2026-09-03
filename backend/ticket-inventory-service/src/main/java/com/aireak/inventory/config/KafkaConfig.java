package com.aireak.inventory.config;

import com.aireak.common.kafka.AbstractKafkaConsumerConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Kafka consumer configuration for ticket-inventory-service. Every bean lives in
 * {@link AbstractKafkaConsumerConfig}, which is where the reasoning behind each one is written
 * down; this service's listeners are its {@code @KafkaListener} methods, with
 * {@code ShowtimeAddedDeadLetterConsumer} on the String-typed dead-letter factory.
 *
 * <p>{@code @EnableKafka} has to be repeated here rather than inherited — annotations are read
 * off the class Spring scans, and only this one is in a scanned package. It is also not optional:
 * see {@link AbstractKafkaConsumerConfig} for what silently stops working without it.
 */
@Configuration
@EnableKafka
public class KafkaConfig extends AbstractKafkaConsumerConfig {
}
