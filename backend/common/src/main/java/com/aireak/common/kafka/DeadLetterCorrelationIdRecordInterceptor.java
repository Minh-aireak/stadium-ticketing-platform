package com.aireak.common.kafka;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.web.filter.CorrelationIdFilter;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * The dead-letter counterpart of {@link CorrelationIdRecordInterceptor}: puts the originating
 * request's correlation ID into the MDC while a {@code -dlt} record is being handled.
 *
 * <p>A separate class because the dead-letter listeners deliberately consume {@code String} rather
 * than {@link EventEnvelope} — a record lands on a {@code -dlt} topic either because the listener
 * rejected a well-formed envelope or because deserializing it failed in the first place, and a
 * typed deserializer would fail on that second case and take the alert listener down with it. That
 * leaves the {@code traceId} readable only out of the raw JSON, which is what this does.
 *
 * <p>Worth the parse: these listeners log the one line that says a message died and a human has to
 * intervene. Untagged, it is the single hardest line in the platform to trace back to the request
 * that produced it — the trail otherwise ends at a retry exhaustion several hops away.
 */
public class DeadLetterCorrelationIdRecordInterceptor implements RecordInterceptor<String, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                                                    Consumer<String, String> consumer) {
        MDC.put(CorrelationIdFilter.MDC_KEY, resolveTraceId(record.value()));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        // afterRecord rather than success(): it runs on the failure path too, and listener threads
        // are pooled — a leaked MDC entry would stamp the next record with this one's ID.
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    private static String resolveTraceId(String value) {
        if (value != null && !value.isBlank()) {
            try {
                JsonNode traceId = MAPPER.readTree(value).path("traceId");
                if (traceId.isString() && !traceId.stringValue().isBlank()) {
                    return traceId.stringValue();
                }
            } catch (RuntimeException e) {
                // Expected for a record that reached the DLT because deserialization failed: the
                // value is then whatever raw bytes arrived, and no ID exists in it to find. Falling
                // through to a fresh ID is the point — never let the alert itself fail to log.
            }
        }
        return UUID.randomUUID().toString();
    }
}
