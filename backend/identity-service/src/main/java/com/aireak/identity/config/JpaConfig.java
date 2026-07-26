package com.aireak.identity.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Enables @CreatedDate / @LastModifiedDate from BaseAuditEntity. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
