package com.aireak.identity.adapter.in.web;

import com.aireak.identity.application.port.in.LoginUseCase;
import com.aireak.identity.application.port.in.LogoutUseCase;
import com.aireak.identity.application.port.in.RefreshTokenUseCase;
import com.aireak.identity.application.port.in.RegisterAccountUseCase;
import com.aireak.identity.application.port.in.RequestPasswordResetUseCase;
import com.aireak.identity.application.port.in.ResendVerificationUseCase;
import com.aireak.identity.application.port.in.ResetPasswordUseCase;
import com.aireak.identity.application.port.in.VerifyEmailUseCase;
import com.aireak.identity.config.AuthCookieProperties;
import com.aireak.identity.config.CorsProperties;
import com.aireak.identity.config.RefreshTokenProperties;
import com.aireak.identity.config.SecurityConfig;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import com.aireak.identity.domain.exception.InvalidVerificationTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /verify-email} is navigated to by a browser clicking a link in an email, so every
 * outcome it can have has to be a page, not a JSON document. The controller already renders one
 * for a bad token and for a Redis outage; the third way this endpoint can fail had no handler and
 * fell through to common's {@code GlobalExceptionHandler}, which answers
 * {@code application/problem+json}.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties({CorsProperties.class, AuthCookieProperties.class, RefreshTokenProperties.class})
@TestPropertySource(properties = {
        "cors.allowed-origins=http://localhost:5173",
        "auth-cookie.name=refresh_token",
        "auth-cookie.path=/api/v1/auth",
        "auth-cookie.domain=",
        "auth-cookie.secure=false",
        "auth-cookie.same-site=Strict",
        "refresh-token.absolute-ttl-seconds=2592000",
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret=",
        "jwt.excluded-paths[0]=/api/v1/auth/verify-email"
})
class AuthControllerVerifyEmailPageTest {

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
    @MockitoBean
    private ResendVerificationUseCase resendVerificationUseCase;
    @MockitoBean
    private RequestPasswordResetUseCase requestPasswordResetUseCase;
    @MockitoBean
    private ResetPasswordUseCase resetPasswordUseCase;

    @Test
    void rendersAPageWhenTheTokenIsRejected() throws Exception {
        doThrow(new InvalidVerificationTokenException("Verification token is invalid or has expired"))
                .when(verifyEmailUseCase).execute(any());

        mockMvc.perform(get("/api/v1/auth/verify-email").param("token", "stale"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    /**
     * {@code AccountActivationSteps} forgives an already-ACTIVE account but not a SUSPENDED one,
     * so a suspended account following a still-valid link reaches {@code Account#activate()} and
     * is refused. The customer is in a browser; they must get the page, not a problem document.
     */
    @Test
    void rendersAPageWhenTheAccountCannotBeActivated() throws Exception {
        doThrow(new InvalidAccountStatusException(
                "Account can only be activated from PENDING_VERIFICATION, current: SUSPENDED"))
                .when(verifyEmailUseCase).execute(any());

        mockMvc.perform(get("/api/v1/auth/verify-email").param("token", "good-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
