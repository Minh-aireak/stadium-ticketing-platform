package com.aireak.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "email-verification")
public record EmailVerificationProperties(long ttlSeconds) {
}
