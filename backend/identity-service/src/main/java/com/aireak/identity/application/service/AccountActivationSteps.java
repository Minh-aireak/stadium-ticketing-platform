package com.aireak.identity.application.service;

import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.domain.exception.InvalidVerificationTokenException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.AccountStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of email verification: everything that must commit or roll back together.
 *
 * <p>Its own bean because {@code @Transactional} is proxy-based, so a self-call from
 * {@link VerifyEmailService} would not open a transaction — the same arrangement
 * {@code NotificationRecordingSteps} and {@code BookingSagaSteps} use. What it buys here is the
 * ability to spend the verification token <em>outside</em> this boundary, once the activation has
 * actually committed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AccountActivationSteps {

    private final AccountRepository accountRepository;
    private final DomainEventPublisher eventPublisher;

    /**
     * Idempotent by status. An account that is already ACTIVE returns quietly rather than throwing
     * {@code InvalidAccountStatusException}, which is what {@link Account#activate()} would do.
     *
     * <p>That case is now reachable in a way it was not before: the token is no longer deleted in
     * the same breath as it is read, so two clicks arriving together can both resolve it. Treating
     * the loser as success is the stance the platform already takes for a replayed step —
     * {@code StripeWebhookService} does it for a payment already reconciled, and a replayed seat
     * hold counts as placed rather than as a conflict. It is also the better answer for a customer
     * who simply clicked the link twice: "verified" both times, instead of an error page about an
     * account that is, in fact, verified.
     *
     * <p>Only ACTIVE is forgiven. A SUSPENDED account still fails, because activating one is a
     * real transition that {@link Account#activate()} refuses for a reason.
     */
    @Transactional
    public void activate(AccountId accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new InvalidVerificationTokenException("Unknown account for verification token"));

        if (account.getStatus() == AccountStatus.ACTIVE) {
            log.info("Account already active, verification link is a no-op: id={}", accountId);
            return;
        }

        account.activate();
        accountRepository.save(account);
        eventPublisher.publishAll(account.pullDomainEvents());
    }
}
