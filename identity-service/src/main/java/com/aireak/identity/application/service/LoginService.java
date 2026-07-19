package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.LoginUseCase;
import com.aireak.identity.application.port.in.command.LoginCommand;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.application.port.out.TokenGeneratorPort;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import com.aireak.identity.domain.model.AccountStatus;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.RawPassword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service: authenticates a user and returns a JWT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService implements LoginUseCase {

    private final AccountRepository accountRepository;
    private final PasswordHashPort passwordHashPort;
    private final TokenGeneratorPort tokenGeneratorPort;

    @Override
    @Transactional(readOnly = true)
    public String execute(LoginCommand command) {
        Email email = new Email(command.email());
        RawPassword rawPassword = new RawPassword(command.rawPassword());

        var account = accountRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidAccountStatusException(
                        "Invalid credentials")); // intentionally vague — no user enumeration

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidAccountStatusException(
                    "Account is not active, current status: " + account.getStatus());
        }

        if (!passwordHashPort.matches(rawPassword, account.getPassword())) {
            throw new InvalidAccountStatusException("Invalid credentials");
        }

        String token = tokenGeneratorPort.generateToken(account);
        log.info("Login successful: accountId={}", account.getId());
        return token;
    }
}
