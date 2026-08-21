package com.aireak.common.kafka;

import com.aireak.common.web.filter.CorrelationIdFilter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterCorrelationIdRecordInterceptorTest {

    private static final String TRACE_ID = "3f2a1b9c-4d5e-4f6a-8b7c-9d0e1f2a3b4c";

    private final DeadLetterCorrelationIdRecordInterceptor interceptor =
            new DeadLetterCorrelationIdRecordInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void readsTheTraceIdOffTheDeadLetteredEnvelope() {
        interceptor.intercept(recordWithValue(
                "{\"eventId\":\"e-1\",\"eventType\":\"catalog.showtime.created\",\"traceId\":\""
                        + TRACE_ID + "\",\"payload\":{}}"), null);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(TRACE_ID);
    }

    /** The record reached the DLT because deserialization itself failed: no ID to find. */
    @Test
    void mintsAnIdWhenTheValueIsNotJson() {
        interceptor.intercept(recordWithValue("<<corrupt bytes>>"), null);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotBlank();
    }

    @Test
    void mintsAnIdWhenTheEnvelopeCarriesNoTraceId() {
        interceptor.intercept(recordWithValue("{\"eventId\":\"e-1\",\"traceId\":null}"), null);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotBlank();
    }

    @Test
    void clearsTheMdcAfterTheRecord() {
        ConsumerRecord<String, String> record = recordWithValue("{\"traceId\":\"" + TRACE_ID + "\"}");
        interceptor.intercept(record, null);

        interceptor.afterRecord(record, null);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private static ConsumerRecord<String, String> recordWithValue(String value) {
        return new ConsumerRecord<>("catalog.showtime.created-dlt", 0, 0L, "key-1", value);
    }
}
