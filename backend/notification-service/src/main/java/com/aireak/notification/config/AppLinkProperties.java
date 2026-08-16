package com.aireak.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two base URLs notification-service turns event payloads into clickable links with. Both are
 * opened by an end user's browser, so neither may be a docker-internal hostname — see
 * application.yaml, where both are required with no default.
 *
 * <p>{@code identityServiceBaseUrl} backs the verify-email link (identity-service serves that page
 * itself); {@code frontendBaseUrl} backs the reset-password link, which has to land on the SPA
 * because submitting a new password is a POST.
 */
@ConfigurationProperties(prefix = "app")
public record AppLinkProperties(String identityServiceBaseUrl, String frontendBaseUrl) {
}
