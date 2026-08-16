package com.aireak.identity.scheduling;

import com.aireak.identity.adapter.out.persistence.outbox.OutboxEventCleanupScheduler;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@code com.aireak.common.scheduling.SchedulerLockConfig} is actually in effect.
 *
 * <p>This exists because the failure mode it guards against is silence. If the config is skipped
 * — a wrong {@code @Conditional}, ShedLock missing from a service's pom, the advisor not
 * proxying — then every {@code @SchedulerLock} degrades to a plain method call, every replica
 * runs every job, and <em>nothing anywhere reports it</em>. The build stays green and the damage
 * only shows up as duplicated work in production. So the wiring is asserted here rather than
 * reasoned about.
 *
 * <p>identity-service stands in for all six services that run scheduled jobs: they share the one
 * config in {@code common} and the same {@code shedlock} table shape, so a break in the shared
 * wiring surfaces here.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=https://auth.aireak.com",
        "jwt.audience=aireak-platform",
        "jwt.previous-secret=",
        "jwt.internal-secret=",
        "cors.allowed-origins=http://localhost:5173",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password="
})
class SchedulerLockIntegrationTest {

    private static final String LOCK_NAME = "identity-outboxCleanup";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("identity_db")
            .withUsername("aireak")
            .withPassword("aireak");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private RedissonClient redissonClient;

    @Autowired
    private LockProvider lockProvider;

    @Autowired
    private OutboxEventCleanupScheduler cleanupScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The config is component-scanned out of {@code common}, not auto-configured, so its
     * {@code @Conditional} is the fragile part — this fails if it ever stops matching.
     */
    @Test
    void theSharedLockProviderIsWiredIntoTheService() {
        assertThat(lockProvider).isInstanceOf(JdbcTemplateLockProvider.class);
    }

    /**
     * The end-to-end proof: the advisor intercepts the annotated method and the provider writes a
     * row. Without a working {@code @SchedulerLock} the table stays empty forever.
     */
    @Test
    void runningALockedJobRegistersItsLock() {
        cleanupScheduler.cleanup();

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from shedlock where name = ?", Integer.class, LOCK_NAME);

        assertThat(rows)
                .as("no shedlock row for '%s' — @SchedulerLock is not being applied", LOCK_NAME)
                .isEqualTo(1);
    }

    /**
     * The scenario the whole thing exists for: a second replica invoking the same job while the
     * first still holds the lock must be skipped, not run alongside it.
     *
     * <p>{@code lockAtLeastFor = PT1M} keeps the lock held well past the end of this test, so a
     * second invocation is guaranteed to land inside the window. ShedLock leaves
     * {@code locked_at} untouched when it skips, so an unchanged value is the evidence that the
     * body did not execute a second time.
     */
    @Test
    void aSecondInvocationIsSkippedWhileTheLockIsStillHeld() {
        cleanupScheduler.cleanup();
        Timestamp afterFirstRun = lockedAt();

        cleanupScheduler.cleanup();

        assertThat(lockedAt())
                .as("locked_at moved, so the job ran again despite the lock being held")
                .isEqualTo(afterFirstRun);
    }

    private Timestamp lockedAt() {
        return jdbcTemplate.queryForObject(
                "select locked_at from shedlock where name = ?", Timestamp.class, LOCK_NAME);
    }
}
