package com.aireak.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA configuration for notification-service.
 * Enables Spring Data auditing so BaseAuditEntity's createdAt/updatedAt are populated.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
    // No additional beans needed — auditing is enabled via annotation
}
