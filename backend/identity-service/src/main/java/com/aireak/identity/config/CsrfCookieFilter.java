package com.aireak.identity.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces resolution of the deferred {@link CsrfToken} on every request so
 * {@code CookieCsrfTokenRepository} actually writes the {@code XSRF-TOKEN} cookie.
 *
 * <p>Spring Security's {@code CsrfFilter} only stashes a *deferred* supplier as a request
 * attribute — the token (and therefore the {@code Set-Cookie} header) is never materialized
 * unless something calls {@code .getToken()} on it. Nothing in this SPA-facing API reads the
 * token server-side (the frontend reads it straight from the cookie), so without this filter the
 * browser never receives {@code XSRF-TOKEN} and every state-changing request the SPA later sends
 * (e.g. {@code POST /refresh}) is rejected with 403 — invisible to unit/slice tests since they
 * don't exercise the real filter chain end-to-end.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
