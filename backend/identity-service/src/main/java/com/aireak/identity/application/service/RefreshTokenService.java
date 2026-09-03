package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.RefreshTokenUseCase;
import com.aireak.identity.application.port.in.dto.AuthResult;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.exception.InvalidRefreshTokenException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.AccountStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Application service: rotates a refresh token and issues a fresh access token.
 *
 * <p>Rotation itself (validation + reuse detection) is delegated to
 * {@link RefreshSessionStorePort#rotate(String)}, which is atomic. This service only reacts
 * to the outcome — never logs the raw token.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}, for the reason
 * {@code LoginService} sets out: one repository read and no writes, so a transaction added no
 * atomicity and only widened a Hikari connection's hold to cover the Redis rotation script that
 * runs before it. This is the most frequently called endpoint on the platform — every tab that
 * comes back from a refresh hits it — so it is the worst place to hold a connection across
 * someone else's round trip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService implements RefreshTokenUseCase {

    private final RefreshSessionStorePort refreshSessionStorePort;
    private final AccountRepository accountRepository;
    private final TokenGeneratorPort tokenGeneratorPort;
    private final JwtProperties jwtProperties;

    @Override
    public AuthResult execute(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException("Missing refresh token");
        }

        var rotated = refreshSessionStorePort.rotate(rawRefreshToken);

        AccountId accountId = AccountId.of(rotated.accountId());
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new InvalidRefreshTokenException("Unknown account for refresh session"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            // InvalidRefreshTokenException, not InvalidAccountStatusException, and the status stays
            // out of the message. Two reasons, and each one alone would be enough.
            //
            // AuthController clears the refresh cookie for this exception and for
            // RefreshTokenReuseException, and for nothing else. A domain exception fell through to
            // common's GlobalExceptionHandler as a 422 with the cookie untouched -- but rotate()
            // above has ALREADY advanced the session's tokenHash, so the cookie the browser keeps
            // is stale from that moment. Its next refresh presents the old token against the
            // rotated hash, ROTATE_SCRIPT reads that as reuse, and the platform logs "Refresh token
            // reuse detected" about a customer who did nothing.
            //
            // And "Account is not active, current status: SUSPENDED" went to the client. LoginService
            // answers an unknown email, a wrong password and a disabled account with one identical
            // message on purpose, paying for a dummy bcrypt comparison to keep it that way; refresh
            // must not hand back what login spends that on withholding.
            log.warn("Refresh rejected: accountId={}, status={}", account.getId(), account.getStatus());
            throw new InvalidRefreshTokenException("Refresh session is no longer valid");
        }

        String accessToken = tokenGeneratorPort.generateToken(account);
        log.info("Refresh successful: accountId={}", account.getId());

        return new AuthResult(accessToken, Instant.now().plusSeconds(jwtProperties.expirationSeconds()), rotated);
    }
}
