package com.aireak.common.scheduling;

import com.aireak.common.web.client.CorrelationIdRequestInitializer;
import com.aireak.common.web.filter.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/**
 * Gives every {@code @Scheduled} run its own correlation ID, so the reconcilers, the outbox
 * cleanups and the payment alert job are traceable as units instead of logging under an empty
 * {@code correlationId} field.
 *
 * <p>A fresh ID per run rather than a propagated one: a scheduled job has no inbound request to
 * inherit from, and it genuinely is the start of its own chain. What it buys is more than tidier
 * log lines — with the ID in the MDC, {@link CorrelationIdRequestInitializer} now puts it on the
 * job's downstream HTTP calls and {@code OutboxEventPublisher} now stamps it on the events the job
 * emits, so a booking driven forward by {@code InventoryConfirmationReconciler} can be followed
 * from the job through ticket-inventory-service and on into whatever consumed the resulting event.
 *
 * <p>Both customizers are registered because Spring Boot picks the scheduler type from {@code
 * spring.threads.virtual.enabled}: a {@code SimpleAsyncTaskScheduler} when virtual threads are on
 * (as every service here has them), a {@code ThreadPoolTaskScheduler} when they are not. Covering
 * both means flipping that flag cannot silently switch correlation IDs back off.
 */
@Configuration
public class CorrelationIdSchedulingConfig {

    /**
     * Not exposed as a {@link TaskDecorator} bean: Spring Boot would then also apply it to the
     * auto-configured task executor, quietly widening this from "scheduled jobs" to every
     * {@code @Async} call. The two customizers below apply it exactly where it is meant to go.
     */
    private static final TaskDecorator CORRELATION_ID_DECORATOR = runnable -> () -> {
        MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString());
        try {
            runnable.run();
        } finally {
            // Scheduler threads outlive the run — a leaked entry would tag the next job, and every
            // event and HTTP call it makes, with the previous job's ID.
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    };

    @Bean
    public SimpleAsyncTaskSchedulerCustomizer correlationIdSimpleAsyncTaskSchedulerCustomizer() {
        return scheduler -> scheduler.setTaskDecorator(CORRELATION_ID_DECORATOR);
    }

    @Bean
    public ThreadPoolTaskSchedulerCustomizer correlationIdThreadPoolTaskSchedulerCustomizer() {
        return scheduler -> scheduler.setTaskDecorator(CORRELATION_ID_DECORATOR);
    }
}
