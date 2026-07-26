package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.LoginUseCase;
import com.aireak.identity.application.port.in.command.LoginCommand;
import com.aireak.identity.application.port.in.dto.AuthResult;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountStatus;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Application service: authenticates a user and issues an access token + refresh session.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService implements LoginUseCase {

    private static final String DUMMY_PASSWORD_FOR_TIMING = "Dummy-Timing-Guard1";

    private final AccountRepository accountRepository;
    private final PasswordHashPort passwordHashPort;
    private final TokenGeneratorPort tokenGeneratorPort;
    private final RefreshSessionStorePort refreshSessionStorePort;
    private final JwtProperties jwtProperties;

    /**
     * Precomputed hash used to run a real bcrypt comparison even when the email
     * isn't found — otherwise the unknown-email path returns near-instantly while
     * the known-email path pays the bcrypt cost, letting an attacker enumerate
     * registered emails purely from response time.
     */
    private HashedPassword dummyHash;

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordHashPort.hash(new RawPassword(DUMMY_PASSWORD_FOR_TIMING));
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResult execute(LoginCommand command) {
        Email email = new Email(command.email());
        RawPassword rawPassword = RawPassword.forAuthentication(command.rawPassword());

        Optional<Account> accountOpt = accountRepository.findByEmail(email);
        HashedPassword passwordToCheck = accountOpt.map(Account::getPassword).orElse(dummyHash);
        boolean passwordMatches = passwordHashPort.matches(rawPassword, passwordToCheck);

        var account = accountOpt.orElseThrow(() -> new InvalidAccountStatusException(
                "Invalid credentials")); // intentionally vague — no user enumeration

        if (account.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Login rejected: accountId={}, status={}", account.getId(), account.getStatus());
            throw new InvalidAccountStatusException("Invalid credentials");
        }

        if (!passwordMatches) {
            throw new InvalidAccountStatusException("Invalid credentials");
        }

        String accessToken = tokenGeneratorPort.generateToken(account);
        var refreshToken = refreshSessionStorePort.createSession(account.getId());
        log.info("Login successful: accountId={}", account.getId());

        return new AuthResult(accessToken, Instant.now().plusSeconds(jwtProperties.expirationSeconds()), refreshToken);
    }
}
