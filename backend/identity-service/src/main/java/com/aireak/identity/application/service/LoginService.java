package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.LoginUseCase;
import com.aireak.identity.application.port.in.command.LoginCommand;
import com.aireak.identity.application.port.in.dto.AuthResult;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.LoginAttemptLimiterPort;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.config.JwtProperties;
import com.aireak.identity.domain.exception.InvalidCredentialsException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountStatus;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Application service: authenticates a user and issues an access token + refresh session.
 *
 * <p>Two defences that are easy to mistake for each other sit here. The dummy bcrypt comparison
 * stops the response time from saying whether an email is registered. {@link
 * LoginAttemptLimiterPort} stops one account's password from being guessed at indefinitely: the
 * gateway limits login by IP ({@code LOGIN(IP, 1, 120, 12)}), which does nothing about an attacker
 * spreading the same account's guesses over many addresses. Neither is a lock — see the port's
 * javadoc for why this deliberately does not disable the account.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. It reads one row and writes none,
 * so a transaction bought no atomicity — what it did buy was a Hikari connection held for the
 * whole method, and most of this method is not database work: a Redis throttle check, a bcrypt
 * comparison at strength 12 that is meant to cost hundreds of milliseconds, and one or two more
 * Redis calls. At the default pool size that put a ceiling on concurrent logins set by connections
 * held during CPU work that needs none. Same reasoning {@code PaymentService} already applies to
 * the gateway call it refuses to hold a connection across.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService implements LoginUseCase {

    private static final String DUMMY_PASSWORD_FOR_TIMING = "Dummy-Timing-Guard1";

    private final AccountRepository accountRepository;
    private final LoginAttemptLimiterPort loginAttemptLimiterPort;
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
    public AuthResult execute(LoginCommand command) {
        Email email = new Email(command.email());
        RawPassword rawPassword = RawPassword.forAuthentication(command.rawPassword());

        // Before the repository read and the bcrypt comparison, not after: paying for both on
        // every attempt is what an attacker wants, and refusing early costs them the round trip
        // for nothing. It leaks no more than the rest of this method already does — the counter
        // is keyed on the submitted address whether or not an account has it, so a throttled
        // response says the address has been tried a lot, not that it is registered.
        if (loginAttemptLimiterPort.isThrottled(email)) {
            log.warn("Login rejected: too many recent failures for the supplied email");
            throw new InvalidCredentialsException("Invalid credentials");
        }

        Optional<Account> accountOpt = accountRepository.findByEmail(email);
        HashedPassword passwordToCheck = accountOpt.map(Account::getPassword).orElse(dummyHash);
        boolean passwordMatches = passwordHashPort.matches(rawPassword, passwordToCheck);

        if (accountOpt.isEmpty()) {
            log.warn("Login rejected: no account for supplied email");
            loginAttemptLimiterPort.recordFailure(email);
            throw new InvalidCredentialsException("Invalid credentials"); // intentionally vague — no user enumeration
        }
        var account = accountOpt.get();

        if (account.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Login rejected: accountId={}, status={}", account.getId(), account.getStatus());
            loginAttemptLimiterPort.recordFailure(email);
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (!passwordMatches) {
            log.warn("Login rejected: accountId={}, reason=bad-password", account.getId());
            loginAttemptLimiterPort.recordFailure(email);
            throw new InvalidCredentialsException("Invalid credentials");
        }

        String accessToken = tokenGeneratorPort.generateToken(account);
        var refreshToken = refreshSessionStorePort.createSession(account.getId());
        // Whoever holds the password is the owner, so the failures before this one were noise
        // (their own typos) or someone else's guesses that did not land. Either way, counting them
        // against the next attempt would only punish the person who just proved who they are.
        loginAttemptLimiterPort.reset(email);
        log.info("Login successful: accountId={}", account.getId());

        return new AuthResult(accessToken, Instant.now().plusSeconds(jwtProperties.expirationSeconds()), refreshToken);
    }
}
