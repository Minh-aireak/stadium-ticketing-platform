package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.RefreshTokenUseCase;
import com.aireak.identity.application.port.in.dto.AuthResult;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
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
            throw new InvalidAccountStatusException(
                    "Account is not active, current status: " + account.getStatus());
        }

        String accessToken = tokenGeneratorPort.generateToken(account);
        log.info("Refresh successful: accountId={}", account.getId());

        return new AuthResult(accessToken, Instant.now().plusSeconds(jwtProperties.expirationSeconds()), rotated);
    }
}
