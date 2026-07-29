package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.RegisterAccountUseCase;
import com.aireak.identity.application.port.in.command.RegisterAccountCommand;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.application.port.out.EmailVerificationTokenPort;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.domain.exception.EmailAlreadyRegisteredException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.RawPassword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service: orchestrates account registration.
 *
 * <p>Responsibilities (hexagonal rule — application layer only):
 * <ol>
 *   <li>Check for duplicate email (idempotency / domain invariant guard)</li>
 *   <li>Hash the raw password (infra concern via port)</li>
 *   <li>Call {@link Account#register} factory — domain logic stays in aggregate</li>
 *   <li>Persist via repository port</li>
 *   <li>Pull domain events and publish to Kafka</li>
 * </ol>
 *
 * <p>No business logic lives here — just orchestration of ports.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterAccountService implements RegisterAccountUseCase {

    private final AccountRepository accountRepository;
    private final PasswordHashPort passwordHashPort;
    private final DomainEventPublisher eventPublisher;
    private final EmailVerificationTokenPort verificationTokenPort;

    @Override
    @Transactional
    public String execute(RegisterAccountCommand command) {
        Email email = new Email(command.email());
        RawPassword rawPassword = new RawPassword(command.rawPassword());

        // Idempotency check at application layer
        if (accountRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email.value());
        }

        var hashedPassword = passwordHashPort.hash(rawPassword);
        String verificationToken = verificationTokenPort.generate();
        Account account = Account.register(email, hashedPassword, verificationToken);

        accountRepository.save(account);
        // Bound to accountId only after the account row exists, so a token never outlives (or
        // precedes) the account it activates.
        verificationTokenPort.store(verificationToken, account.getId());

        // Publish domain events after successful persistence
        var events = account.pullDomainEvents();
        eventPublisher.publishAll(events);

        log.info("Account registered: id={}, email={}", account.getId(), email);
        return account.getId().toString();
    }
}
