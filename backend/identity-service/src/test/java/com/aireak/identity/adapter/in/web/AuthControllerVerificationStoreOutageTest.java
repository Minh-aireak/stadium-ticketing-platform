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
import com.aireak.identity.domain.exception.VerificationTokenStoreUnavailableException;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Three endpoints can raise {@link VerificationTokenStoreUnavailableException} and only one of
 * them is a browser navigation.
 *
 * <p>{@code RedisEmailVerificationTokenAdapter} raises it from {@code store} as well as from
 * {@code peek}, and {@code store} runs inside {@code POST /register} (RegisterAccountService binds
 * the token once the account row exists) and inside {@code POST /resend-verification} — both XHR
 * calls from the SPA, neither able to do anything with an HTML document.
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
        "jwt.excluded-paths[0]=/api/v1/auth/verify-email",
        "jwt.excluded-paths[1]=/api/v1/auth/register",
        "jwt.excluded-paths[2]=/api/v1/auth/resend-verification"
})
class AuthControllerVerificationStoreOutageTest {

    private static final String OUTAGE_TYPE =
            "https://aireak.com/errors/verification-token-store-unavailable";

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

    private static VerificationTokenStoreUnavailableException outage() {
        return new VerificationTokenStoreUnavailableException(
                "Failed to store verification token", new RuntimeException("connection refused"));
    }

    @Test
    void answersRegistrationWithAProblemDocument() throws Exception {
        when(registerAccountUseCase.execute(any())).thenThrow(outage());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"Password1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(OUTAGE_TYPE))
                .andExpect(jsonPath("$.detail").value("Email verification is temporarily unavailable"));
    }

    @Test
    void answersAVerificationResendWithAProblemDocument() throws Exception {
        doThrow(outage()).when(resendVerificationUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pending@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(OUTAGE_TYPE));
    }

    /**
     * Guard for the half that must not change: {@code GET /verify-email} is followed out of an
     * email, so every outcome it has is a page — see {@code AuthControllerVerifyEmailPageTest} for
     * the other two.
     */
    @Test
    void stillRendersAPageWhenTheOutageHitsTheEmailLink() throws Exception {
        doThrow(outage()).when(verifyEmailUseCase).execute(any());

        mockMvc.perform(get("/api/v1/auth/verify-email").param("token", "good-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
