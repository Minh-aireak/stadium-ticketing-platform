package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.ResendVerificationUseCase;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.application.port.out.EmailVerificationTokenPort;
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
 * Application service: mints a fresh email verification token and re-publishes
 * {@code AccountRegisteredEvent} so notification-service sends the welcome email again.
 *
 * <p><strong>Never reveals whether an address is registered.</strong> An unknown email, a
 * malformed email, and an account that is already ACTIVE all take the same silent path as a
 * successful resend, so this endpoint cannot be used to enumerate accounts — or, worse than the
 * enumeration {@code RequestPasswordResetService} guards against, to sort registered addresses
 * into verified and unverified. Only the server log distinguishes them.
 *
 * <p>The old token is deliberately not deleted. Both remain valid until their TTLs run out, and
 * either activates the account exactly once; deleting it would break the link in a mail the
 * customer may be about to click, which is the failure this endpoint exists to undo. That is safe
 * because a verification token grants nothing but a transition out of PENDING_VERIFICATION, and
 * {@code AccountActivationSteps} treats a second one as a no-op.
 *
 * <p>Transactional, unlike {@link VerifyEmailService}: the outbox insert is the only durable write
 * and the token store happens before it. A Redis store that succeeds under a rolled-back
 * transaction leaves an unusable token in Redis until its TTL, which costs nothing — the opposite
 * ordering is the one that strands an account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResendVerificationService implements ResendVerificationUseCase {

    private final AccountRepository accountRepository;
    private final EmailVerificationTokenPort verificationTokenPort;
    private final DomainEventPublisher eventPublisher;

    @Override
    @Transactional
    public void execute(String email) {
        Optional<Account> account = findAccount(email);
        if (account.isEmpty()) {
            log.info("Verification resend requested for an address with no account — nothing sent");
            return;
        }

        String verificationToken = verificationTokenPort.generate();
        try {
            account.get().reissueVerification(verificationToken);
        } catch (InvalidAccountStatusException ex) {
            log.info("Verification resend requested for an account that is not awaiting verification "
                    + "— nothing sent: id={}", account.get().getId());
            return;
        }

        // Bound to the account only once the request is known to be legitimate, so an unusable
        // token is never left sitting in Redis.
        verificationTokenPort.store(verificationToken, account.get().getId());
        eventPublisher.publishAll(account.get().pullDomainEvents());

        log.info("Verification email resent: id={}", account.get().getId());
    }

    private Optional<Account> findAccount(String email) {
        try {
            return accountRepository.findByEmail(new Email(email));
        } catch (InvalidEmailException ex) {
            log.info("Verification resend requested for a malformed email address — nothing sent");
            return Optional.empty();
        }
    }
}
