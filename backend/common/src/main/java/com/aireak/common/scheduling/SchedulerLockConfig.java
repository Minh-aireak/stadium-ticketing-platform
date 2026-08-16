package com.aireak.common.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Makes every {@code @Scheduled} method annotated with {@code @SchedulerLock} run on at most one
 * instance at a time.
 *
 * <p>Every scheduled job in this platform is written as if it were the only one running: the
 * outbox cleanups issue an unqualified bulk DELETE, and the reconciliation jobs read a set of
 * stuck rows and then act on each. At one replica per service that holds. At two it stops
 * holding — two reconcilers would both see the same stuck booking and both drive it forward,
 * which is exactly the double-apply the sagas are built to avoid. This config is what makes
 * scaling a service horizontally safe rather than a silent correctness change.
 *
 * <p>Backed by the JDBC provider rather than the Redis one because it is the only choice every
 * service can use: match-catalog-service and notification-service both run scheduled jobs
 * without Redisson on the classpath, while all six have Postgres and Flyway. The {@code shedlock}
 * table is created by each service's own migration.
 *
 * <p>Lives in {@code common} so there is one copy rather than six. ShedLock is {@code
 * optional=true} there, and optional dependencies are not transitive, so only a service that
 * declares it itself gets it — api-gateway component-scans {@code com.aireak.common} but is
 * WebFlux with no scheduled job and no datasource, and the class guard below leaves it out.
 * The guard is written by name deliberately: Spring evaluates it from bytecode metadata, so the
 * class is never loaded (and its {@link LockProvider} bean signature never resolved) where
 * ShedLock is absent. {@code @ConditionalOnBean(DataSource.class)} is NOT used — on a
 * component-scanned config it is evaluated before autoconfiguration has registered the
 * DataSource, so it would silently skip this class in every service and leave every
 * {@code @SchedulerLock} doing nothing.
 */
@Configuration
@ConditionalOnClass(name = "net.javacrumbs.shedlock.core.LockProvider")
// 10 minutes is the backstop for a JVM that dies mid-job and never releases its lock; every job
// overrides it with its own realistic ceiling.
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        // Take lock timestamps from the database clock, not each JVM's own, so
                        // replicas with skewed clocks cannot read a live lock as already expired.
                        .usingDbTime()
                        .build());
    }
}
