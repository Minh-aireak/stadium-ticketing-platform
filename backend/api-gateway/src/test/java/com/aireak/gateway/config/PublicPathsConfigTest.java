package com.aireak.gateway.config;

import com.aireak.gateway.util.PublicPathMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the shipped application.yaml rather than a hand-built fixture, because what these
 * assertions are protecting is the deployed configuration itself — a fixture listing the right
 * paths proves nothing about the file the gateway actually boots from.
 *
 * <p>Deliberately free of {@code @SpringBootTest}: the sibling context tests pull in Testcontainers
 * Redis, so on a machine without a reachable Docker daemon they error out before asserting
 * anything. A routing rule that decides whether customers can activate their accounts should not
 * be guarded only by a test that silently fails to run.
 */
class PublicPathsConfigTest {

    private final List<String> publicPaths = loadPublicPaths();

    /**
     * The link in the verification email lands here. It targets api-gateway because a deployment
     * publishes the gateway alone (see notification-service's AppLinkProperties), and a browser
     * opening it from a mail client sends no Authorization header — nor could it, since the
     * account being activated cannot log in to obtain a token. Without this entry the gateway
     * answers 401 and the account can never be activated. It stayed hidden while the email pointed
     * straight at identity-service's own port and never crossed the gateway.
     */
    @Test
    void verifyEmailIsPublicForGetSoAClickFromAMailClientReachesIdentityService() {
        assertThat(PublicPathMatcher.isPublic(publicPaths, "GET", "/api/v1/auth/verify-email")).isTrue();
    }

    /** Scoped to the one verb identity-service maps, matching how the catalog reads are scoped. */
    @Test
    void verifyEmailIsNotPublicForOtherVerbs() {
        assertThat(PublicPathMatcher.isPublic(publicPaths, "POST", "/api/v1/auth/verify-email")).isFalse();
    }

    /**
     * Its counterpart: requesting a new link is public for the same reason (an unverified account
     * has no token), and the pair is easy to break by editing one and forgetting the other.
     */
    @Test
    void resendVerificationIsPublicToo() {
        assertThat(PublicPathMatcher.isPublic(publicPaths, "POST", "/api/v1/auth/resend-verification")).isTrue();
    }

    private static List<String> loadPublicPaths() {
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader()
                    .load("application.yaml", new ClassPathResource("application.yaml"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the gateway's application.yaml", e);
        }
        // Flattened by the loader into jwt.public-paths[0], [1], ... — walk until the first gap.
        List<String> paths = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            for (int i = 0; source.containsProperty("jwt.public-paths[" + i + "]"); i++) {
                paths.add(String.valueOf(source.getProperty("jwt.public-paths[" + i + "]")));
            }
        }
        assertThat(paths).as("jwt.public-paths in the gateway's application.yaml").isNotEmpty();
        return paths;
    }
}
