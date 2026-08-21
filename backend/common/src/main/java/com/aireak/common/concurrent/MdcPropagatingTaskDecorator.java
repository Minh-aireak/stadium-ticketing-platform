package com.aireak.common.concurrent;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Carries the submitting thread's MDC — the correlation ID above all — onto work handed to an
 * {@code @Async} executor, so a task that outlives its request still logs under the request that
 * started it.
 *
 * <p>The MDC is a thread-local, so without this every line an async task logs comes out with an
 * empty {@code correlationId} and cannot be joined to anything. That matters most exactly where
 * the work is fire-and-forget: {@code MatchSearchIndexer#indexAsync} has no retry, so its failure
 * log is the only record that a match never made it into Elasticsearch.
 *
 * <p>Copies rather than mints (unlike {@code CorrelationIdSchedulingConfig}, whose scheduled runs
 * genuinely start their own chain) — an async task is a continuation of the request that submitted
 * it, and sharing that request's ID is the whole point.
 */
public class MdcPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Read on the submitting thread, at submit time: the request may well have completed and
        // cleared its own MDC before the pool gets round to running this.
        Map<String, String> submitted = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> original = MDC.getCopyOfContextMap();
            apply(submitted);
            try {
                runnable.run();
            } finally {
                // Restore rather than clear: executor threads are pooled and may be running work
                // that set up its own context around this call.
                apply(original);
            }
        };
    }

    private static void apply(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
