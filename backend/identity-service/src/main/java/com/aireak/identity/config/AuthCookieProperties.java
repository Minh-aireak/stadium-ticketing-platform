package com.aireak.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param domain empty string means host-only cookie (no Domain attribute) — used for local dev.
 */
@ConfigurationProperties(prefix = "auth-cookie")
public record AuthCookieProperties(String name, String path, String domain, boolean secure, String sameSite) {
}
