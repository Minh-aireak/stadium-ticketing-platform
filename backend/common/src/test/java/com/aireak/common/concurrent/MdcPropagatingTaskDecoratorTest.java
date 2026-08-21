package com.aireak.common.concurrent;

import com.aireak.common.web.filter.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcPropagatingTaskDecoratorTest {

    private static final String CORRELATION_ID = "3f2a1b9c-4d5e-4f6a-8b7c-9d0e1f2a3b4c";

    private final MdcPropagatingTaskDecorator decorator = new MdcPropagatingTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void runsTheTaskUnderTheSubmittingThreadsCorrelationId() {
        AtomicReference<String> seen = new AtomicReference<>();
        MDC.put(CorrelationIdFilter.MDC_KEY, CORRELATION_ID);

        Runnable decorated = decorator.decorate(() -> seen.set(MDC.get(CorrelationIdFilter.MDC_KEY)));
        // The request thread finishes and clears its MDC before the pool runs the task.
        MDC.clear();
        decorated.run();

        assertThat(seen.get()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void leavesTheRunningThreadsOwnContextAsItFoundIt() {
        MDC.put(CorrelationIdFilter.MDC_KEY, CORRELATION_ID);
        Runnable decorated = decorator.decorate(() -> {});

        MDC.put(CorrelationIdFilter.MDC_KEY, "a-different-run");
        decorated.run();

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("a-different-run");
    }

    @Test
    void clearsWhatItAddedWhenTheRunningThreadHadNoContext() {
        MDC.put(CorrelationIdFilter.MDC_KEY, CORRELATION_ID);
        Runnable decorated = decorator.decorate(() -> {});

        MDC.clear();
        decorated.run();

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
