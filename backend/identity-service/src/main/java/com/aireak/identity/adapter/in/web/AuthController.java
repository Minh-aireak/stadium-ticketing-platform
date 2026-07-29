package com.aireak.identity.adapter.in.web;

import com.aireak.identity.adapter.in.web.dto.LoginRequest;
import com.aireak.identity.adapter.in.web.dto.LoginResponse;
import com.aireak.identity.adapter.in.web.dto.RegisterRequest;
import com.aireak.identity.adapter.in.web.dto.RegisterResponse;
import com.aireak.identity.application.port.in.LoginUseCase;
import com.aireak.identity.application.port.in.LogoutUseCase;
import com.aireak.identity.application.port.in.RefreshTokenUseCase;
import com.aireak.identity.application.port.in.RegisterAccountUseCase;
import com.aireak.identity.application.port.in.VerifyEmailUseCase;
import com.aireak.identity.application.port.in.command.LoginCommand;
import com.aireak.identity.application.port.in.command.RegisterAccountCommand;
import com.aireak.identity.application.port.in.dto.AuthResult;
import com.aireak.identity.config.AuthCookieProperties;
import com.aireak.identity.config.CorsProperties;
import com.aireak.identity.domain.exception.InvalidRefreshTokenException;
import com.aireak.identity.domain.exception.InvalidVerificationTokenException;
import com.aireak.identity.domain.exception.RefreshSessionStoreUnavailableException;
import com.aireak.identity.domain.exception.RefreshTokenReuseException;
import com.aireak.identity.domain.exception.VerificationTokenStoreUnavailableException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Inbound web adapter — driving adapter for Identity bounded context.
 *
 * <p>Hexagonal rule: this class only translates HTTP <-> use case commands, plus the one
 * HTTP-specific concern use cases must never own: the refresh-token cookie. Access tokens go
 * in the JSON body (frontend keeps them in memory only); the refresh token never appears in
 * a JSON response body and never travels in a URL.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String TYPE_BASE = "https://aireak.com/errors/";

    private final RegisterAccountUseCase registerAccountUseCase;
    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;
    private final VerifyEmailUseCase verifyEmailUseCase;
    private final AuthCookieProperties cookieProperties;
    private final CorsProperties corsProperties;

    /**
     * POST /api/v1/auth/register
     * Registers a new account and triggers email verification.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        String accountId = registerAccountUseCase.execute(
                new RegisterAccountCommand(request.email(), request.password())
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RegisterResponse.of(accountId));
    }

    /**
     * POST /api/v1/auth/login
     * Authenticates, returns a short-lived Bearer access token, and sets the
     * HttpOnly refresh cookie.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = loginUseCase.execute(new LoginCommand(request.email(), request.password()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken().rawToken(),
                        result.refreshToken().expiresAt()).toString())
                .body(toLoginResponse(result));
    }

    /**
     * GET /api/v1/auth/verify-email?token=...
     * Activates the account bound to the (one-time, TTL-bound) email verification token sent in
     * the welcome email. Browser-navigated, so responses are a small HTML page, not JSON.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<String> verifyEmail(@RequestParam("token") String token) {
        verifyEmailUseCase.execute(token);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(verificationPage("Email verified", "Your account is now active — you can log in.", true));
    }

    /**
     * POST /api/v1/auth/refresh
     * Rotates the refresh token (read from the HttpOnly cookie — never from the body or a
     * query param) and issues a new access token. Requires the CSRF header (see SecurityConfig).
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(HttpServletRequest request) {
        String rawRefreshToken = readCookie(request);
        AuthResult result = refreshTokenUseCase.execute(rawRefreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken().rawToken(),
                        result.refreshToken().expiresAt()).toString())
                .body(toLoginResponse(result));
    }

    /**
     * POST /api/v1/auth/logout
     * Revokes the refresh session and clears the cookie. Idempotent and always succeeds
     * from the client's point of view — logging out an already-expired session is not an error.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String rawRefreshToken = readCookie(request);
        logoutUseCase.execute(rawRefreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .build();
    }

    // ---- reuse-detection / invalid-token handling: 401, and always clear the cookie ----

    @ExceptionHandler(RefreshTokenReuseException.class)
    public ResponseEntity<ProblemDetail> handleReuseDetected(RefreshTokenReuseException ex) {
        log.warn("Refresh token reuse detected — session revoked");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Refresh token reuse detected. Please log in again.");
        problem.setType(URI.create(TYPE_BASE + "refresh-token-reuse"));
        problem.setTitle("Refresh Token Reuse Detected");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .body(problem);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "invalid-refresh-token"));
        problem.setTitle("Invalid Refresh Token");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .body(problem);
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<String> handleInvalidVerificationToken(InvalidVerificationTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .body(verificationPage("Verification failed", ex.getMessage(), false));
    }

    @ExceptionHandler(VerificationTokenStoreUnavailableException.class)
    public ResponseEntity<String> handleVerificationStoreUnavailable(VerificationTokenStoreUnavailableException ex) {
        log.error("Verification token store unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_HTML)
                .body(verificationPage("Service unavailable", "Please try again shortly.", false));
    }

    @ExceptionHandler(RefreshSessionStoreUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleStoreUnavailable(RefreshSessionStoreUnavailableException ex) {
        log.error("Refresh session store unavailable", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Authentication service temporarily unavailable");
        problem.setType(URI.create(TYPE_BASE + "session-store-unavailable"));
        problem.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    // ---- helpers ----

    private String verificationPage(String title, String message, boolean success) {
        String color = success ? "#1e7a3a" : "#a31515";
        String loginUrl = (corsProperties.allowedOrigins() == null || corsProperties.allowedOrigins().isEmpty())
                ? "/" : corsProperties.allowedOrigins().get(0) + "/login";
        return """
                <!DOCTYPE html>
                <html>
                <head><title>%s</title>
                <style>
                  body { font-family: Arial, sans-serif; color: #333; display: flex; justify-content: center; padding-top: 60px; }
                  .card { max-width: 480px; padding: 24px; border: 1px solid #ddd; border-radius: 8px; text-align: center; }
                  h2 { color: %s; }
                  a { color: #1e3a8a; }
                </style>
                </head>
                <body>
                  <div class="card">
                    <h2>%s</h2>
                    <p>%s</p>
                    <p><a href="%s">Go to login</a></p>
                  </div>
                </body>
                </html>
                """.formatted(title, color, title, message, loginUrl);
    }

    private LoginResponse toLoginResponse(AuthResult result) {
        long expiresIn = Duration.between(Instant.now(), result.accessTokenExpiresAt()).getSeconds();
        return LoginResponse.bearer(result.accessToken(), Math.max(expiresIn, 0));
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieProperties.name().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie refreshCookie(String rawToken, Instant expiresAt) {
        return baseCookieBuilder(rawToken)
                .maxAge(Duration.between(Instant.now(), expiresAt))
                .build();
    }

    private ResponseCookie expiredCookie() {
        return baseCookieBuilder("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookieBuilder(String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookieProperties.name(), value)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path(cookieProperties.path());
        if (cookieProperties.domain() != null && !cookieProperties.domain().isBlank()) {
            builder.domain(cookieProperties.domain());
        }
        return builder;
    }
}
