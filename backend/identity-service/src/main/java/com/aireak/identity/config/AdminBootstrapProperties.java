package com.aireak.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-supplied credentials for the one-time first-admin bootstrap (see
 * {@link AdminBootstrapRunner}). Both default to empty — bootstrap is a no-op unless an operator
 * explicitly sets {@code ADMIN_BOOTSTRAP_EMAIL} / {@code ADMIN_BOOTSTRAP_PASSWORD}.
 */
@ConfigurationProperties(prefix = "admin-bootstrap")
public record AdminBootstrapProperties(String email, String password) {

    public AdminBootstrapProperties {
        if (email == null) {
            email = "";
        }
        if (password == null) {
            password = "";
        }
    }
}
