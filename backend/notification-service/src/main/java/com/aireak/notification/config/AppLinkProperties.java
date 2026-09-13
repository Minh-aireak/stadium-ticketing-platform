package com.aireak.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two base URLs notification-service turns event payloads into clickable links with. Both are
 * opened by an end user's browser, so neither may be a docker-internal hostname — see
 * application.yaml, where both are required with no default.
 *
 * <p>{@code publicApiBaseUrl} backs the verify-email link. It is the api-gateway's public origin,
 * NOT identity-service's own — a deployment publishes the gateway and nothing behind it, so a link
 * pointing at identity-service directly is unreachable from the recipient's mail client. The
 * gateway carries {@code GET:/api/v1/auth/verify-email} in its {@code jwt.public-paths} for
 * exactly this link's sake; the two must move together.
 *
 * <p>{@code frontendBaseUrl} backs the reset-password link, which has to land on the SPA because
 * submitting a new password is a POST. It is a different origin from {@code publicApiBaseUrl}:
 * the storefront, not the API edge.
 */
@ConfigurationProperties(prefix = "app")
public record AppLinkProperties(String publicApiBaseUrl, String frontendBaseUrl) {
}
