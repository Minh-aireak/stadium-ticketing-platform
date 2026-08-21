package com.aireak.common.kafka;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.web.filter.CorrelationIdFilter;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.util.UUID;

/**
 * Restores the originating request's correlation ID into the MDC while a Kafka record is being
 * handled, so a consumer's log lines join up with the HTTP request that ultimately caused them.
 *
 * <p>The ID is already carried end to end: {@code OutboxEventPublisher} reads it from the MDC at
 * publish time into {@link EventEnvelope}'s {@code traceId}, it is persisted on the outbox row,
 * and Debezium forwards the payload verbatim. Without this interceptor that value arrives and is
 * simply never used — every line a consumer logs would have an empty correlationId, breaking the
 * trail at exactly the asynchronous hop that is hardest to follow by hand.
 *
 * <p>Register on the listener container factory, not on individual listeners:
 * {@code factory.setRecordInterceptor(new CorrelationIdRecordInterceptor())}.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<String, EventEnvelope<?>> {

    @Override
    public ConsumerRecord<String, EventEnvelope<?>> intercept(
            ConsumerRecord<String, EventEnvelope<?>> record,
            Consumer<String, EventEnvelope<?>> consumer) {

        EventEnvelope<?> envelope = record.value();
        String traceId = envelope != null ? envelope.getTraceId() : null;

        // Rare now that CorrelationIdSchedulingConfig gives each @Scheduled run its own ID, which
        // the outbox picks up: an event published by a reconciler carries one just as a request-born
        // event does. Minting one for whatever is left keeps every consumed record traceable as a
        // unit, instead of leaving those log lines with no correlation ID at all.
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(CorrelationIdFilter.MDC_KEY, traceId);
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, EventEnvelope<?>> record,
                            Consumer<String, EventEnvelope<?>> consumer) {
        // afterRecord rather than success(): it runs on the failure path too, and listener threads
        // are pooled — a leaked MDC entry would silently stamp the next record with the previous
        // record's correlation ID.
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
}
