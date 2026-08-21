package com.aireak.common.scheduling;

import com.aireak.common.web.filter.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdSchedulingConfigTest {

    private final CorrelationIdSchedulingConfig config = new CorrelationIdSchedulingConfig();

    /** The scheduler every service actually gets, since all of them run virtual threads. */
    @Test
    void tagsAVirtualThreadScheduledRun() throws Exception {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        config.correlationIdSimpleAsyncTaskSchedulerCustomizer().customize(scheduler);

        assertThat(runOn(scheduler::execute).correlationId()).isNotBlank();
    }

    /** What a service gets instead when {@code spring.threads.virtual.enabled} is turned off. */
    @Test
    void tagsEachPooledRunWithItsOwnId() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        config.correlationIdThreadPoolTaskSchedulerCustomizer().customize(scheduler);
        scheduler.initialize();

        Run first = runOn(scheduler::execute);
        Run second = runOn(scheduler::execute);

        assertThat(first.correlationId()).isNotBlank();
        // Same pooled thread, so this is the assertion that the ID is cleared and re-minted per run
        // rather than a thread keeping whichever run tagged it first.
        assertThat(second.thread()).isEqualTo(first.thread());
        assertThat(second.correlationId()).isNotBlank().isNotEqualTo(first.correlationId());

        scheduler.shutdown();
    }

    private record Run(String correlationId, String thread) {}

    private static Run runOn(Consumer<Runnable> executor) throws Exception {
        AtomicReference<Run> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        executor.accept(() -> {
            seen.set(new Run(MDC.get(CorrelationIdFilter.MDC_KEY), Thread.currentThread().getName()));
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        return seen.get();
    }
}
