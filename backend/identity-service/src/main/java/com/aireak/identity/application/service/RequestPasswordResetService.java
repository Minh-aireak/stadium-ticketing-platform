package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.RequestPasswordResetUseCase;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.application.port.out.PasswordResetTokenPort;
import com.aireak.identity.config.PasswordResetProperties;
import com.aireak.identity.domain.exception.InvalidAccountStatusException;
import com.aireak.identity.domain.exception.InvalidEmailException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.Email;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Application service: issues a one-time password reset token and publishes
 * {@code PasswordResetRequestedEvent} so notification-service can email the link.
 *
 * <p><strong>Never reveals whether an address is registered.</strong> An unknown email, a
 * malformed email, and a non-ACTIVE account all take the same silent path as a successful request:
 * the caller sees an identical response either way, so this endpoint cannot be used to enumerate
 * accounts. Only the server log distinguishes them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestPasswordResetService implements RequestPasswordResetUseCase {

    private final AccountRepository accountRepository;
    private final PasswordResetTokenPort resetTokenPort;
    private final DomainEventPublisher eventPublisher;
    private final PasswordResetProperties properties;

    @Override
    @Transactional
    public void execute(String email) {
        Optional<Account> account = findAccount(email);
        if (account.isEmpty()) {
            log.info("Password reset requested for an address with no account — nothing sent");
            return;
        }

        String resetToken = resetTokenPort.generate();
        try {
            account.get().requestPasswordReset(resetToken, properties.ttlMinutes());
        } catch (InvalidAccountStatusException ex) {
            log.info("Password reset requested for a non-ACTIVE account — nothing sent: id={}",
                    account.get().getId());
            return;
        }

        // Bound to the account only once the request is known to be legitimate, so an unusable
        // token is never left sitting in Redis.
        resetTokenPort.store(resetToken, account.get().getId());
        eventPublisher.publishAll(account.get().pullDomainEvents());

        log.info("Password reset requested: id={}", account.get().getId());
    }

    private Optional<Account> findAccount(String email) {
        try {
            return accountRepository.findByEmail(new Email(email));
        } catch (InvalidEmailException ex) {
            log.info("Password reset requested for a malformed email address — nothing sent");
            return Optional.empty();
        }
    }
}
