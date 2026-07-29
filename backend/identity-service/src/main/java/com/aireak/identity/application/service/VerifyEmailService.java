package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.VerifyEmailUseCase;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.application.port.out.EmailVerificationTokenPort;
import com.aireak.identity.domain.exception.InvalidVerificationTokenException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service: consumes a one-time email verification token and activates the
 * corresponding account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyEmailService implements VerifyEmailUseCase {

    private final EmailVerificationTokenPort verificationTokenPort;
    private final AccountRepository accountRepository;
    private final DomainEventPublisher eventPublisher;

    @Override
    @Transactional
    public void execute(String rawToken) {
        AccountId accountId = verificationTokenPort.consume(rawToken);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new InvalidVerificationTokenException("Unknown account for verification token"));

        account.activate();
        accountRepository.save(account);

        var events = account.pullDomainEvents();
        eventPublisher.publishAll(events);

        log.info("Account activated via email verification: id={}", accountId);
    }
}
