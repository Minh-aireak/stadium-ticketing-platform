package com.aireak.identity.adapter.in.web;

import com.aireak.identity.application.port.in.LoginUseCase;
import com.aireak.identity.application.port.in.LogoutUseCase;
import com.aireak.identity.application.port.in.RefreshTokenUseCase;
import com.aireak.identity.application.port.in.RegisterAccountUseCase;
import com.aireak.identity.application.port.in.VerifyEmailUseCase;
import com.aireak.identity.application.port.in.dto.AuthResult;
import com.aireak.identity.application.port.out.dto.IssuedRefreshToken;
import com.aireak.identity.config.AuthCookieProperties;
import com.aireak.identity.config.CorsProperties;
import com.aireak.identity.config.SecurityConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the REAL Spring Security filter chain (SecurityConfig, wired with the real
 * CookieCsrfTokenRepository + {@link com.aireak.identity.config.CsrfCookieFilter}), not a unit
 * test of the filter in isolation.
 *
 * <p>Before the fix, {@code CookieCsrfTokenRepository} never actually wrote the {@code
 * XSRF-TOKEN} cookie: {@code CsrfFilter} only stashes a *deferred* token supplier on the request,
 * and nothing else in this SPA-facing API ever called {@code .getToken()} to force it to
 * materialize. That meant the browser never received the cookie, so every subsequent
 * CSRF-protected request (like {@code POST /refresh}) was rejected with 403 — a bug invisible to
 * any unit test that doesn't run requests through the actual filter chain.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties({CorsProperties.class, AuthCookieProperties.class})
@TestPropertySource(properties = {
        "cors.allowed-origins=http://localhost:5173",
        "auth-cookie.name=refresh_token",
        "auth-cookie.path=/api/v1/auth",
        "auth-cookie.domain=",
        "auth-cookie.secure=false",
        "auth-cookie.same-site=Strict",
        // Needed to construct the real (common) JwtAuthenticationFilter, which @WebMvcTest
        // auto-detects as a Filter bean even though this controller doesn't require auth itself.
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        // Auth endpoints are permitAll in SecurityConfig and must also be excluded from
        // JwtAuthenticationFilter's interception, otherwise this stateless filter rejects
        // them with 401 before Spring Security's filter chain ever gets to check the
        // requestMatchers permitAll rules.
        "jwt.excluded-paths[0]=/api/v1/auth/register",
        "jwt.excluded-paths[1]=/api/v1/auth/login",
        "jwt.excluded-paths[2]=/api/v1/auth/refresh",
        "jwt.excluded-paths[3]=/api/v1/auth/logout",
        "jwt.excluded-paths[4]=/api/v1/auth/verify-email",
        "jwt.excluded-paths[5]=/actuator/health",
        "jwt.excluded-paths[6]=/actuator/info"
})
class AuthControllerCsrfCookieIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterAccountUseCase registerAccountUseCase;
    @MockitoBean
    private LoginUseCase loginUseCase;
    @MockitoBean
    private RefreshTokenUseCase refreshTokenUseCase;
    @MockitoBean
    private LogoutUseCase logoutUseCase;
    @MockitoBean
    private VerifyEmailUseCase verifyEmailUseCase;

    @Test
    void loginThenForceResolveXsrfTokenViaRejectedRefresh_thenRetryRefreshSucceeds() throws Exception {
        when(loginUseCase.execute(any())).thenReturn(new AuthResult(
                "access-token-1",
                Instant.now().plusSeconds(900),
                new IssuedRefreshToken("session-1", "family-1", "account-1", "raw-refresh-token-1",
                        Instant.now().plusSeconds(2_592_000))
        ));

        // Step 1: login — this endpoint is CSRF-ignored, so no XSRF-TOKEN cookie is set,
        // but the refresh_token cookie IS set (via AuthCookieFilter in the controller).
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Abcdefg1\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("refresh_token");

        // Step 2: hit /refresh without CSRF — CsrfCookieFilter forces token resolution even
        // though the request is rejected, materialising the XSRF-TOKEN cookie in the 403 response.
        MvcResult rejectedResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isForbidden())
                .andReturn();

        Cookie xsrfCookie = rejectedResult.getResponse().getCookie("XSRF-TOKEN");

        when(refreshTokenUseCase.execute(any())).thenReturn(new AuthResult(
                "access-token-2",
                Instant.now().plusSeconds(900),
                new IssuedRefreshToken("session-1", "family-1", "account-1", "raw-refresh-token-2",
                        Instant.now().plusSeconds(2_592_000))
        ));

        // Step 3: now retry with the XSRF-TOKEN echoed as both cookie + header (double-submit).
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie, new Cookie("XSRF-TOKEN", xsrfCookie.getValue()))
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void refreshWithoutTheCsrfHeaderIsStillRejected_fixDidNotAccidentallyDisableCsrfProtection() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", "some-refresh-token")))
                .andExpect(status().isForbidden());
    }
}
