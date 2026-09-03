package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.VerifyEmailUseCase;
import com.aireak.identity.application.port.out.EmailVerificationTokenPort;
import com.aireak.identity.domain.model.AccountId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Application service: consumes a one-time email verification token and activates the
 * corresponding account.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. The token lives in Redis and the
 * account lives in Postgres, and only one of those two can be rolled back. Spending the token
 * first — which is what a single atomic get-and-delete did — meant that any failure afterwards
 * rolled the account back to PENDING_VERIFICATION and left the customer holding a link that no
 * longer works. There is no way out of that state from outside the database: the account cannot
 * log in ({@code LoginService} rejects anything that is not ACTIVE) and cannot be registered again
 * ({@code EmailAlreadyRegisteredException}).
 *
 * <p>So the order is now: resolve the token, commit the activation, then spend the token. A
 * failure in the middle leaves the link exactly as usable as it was a second earlier, and the
 * customer's next click succeeds. See {@link EmailVerificationTokenPort} for what that gives up
 * and {@link AccountActivationSteps} for why it costs nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyEmailService implements VerifyEmailUseCase {

    private final EmailVerificationTokenPort verificationTokenPort;
    private final AccountActivationSteps activationSteps;

    @Override
    public void execute(String rawToken) {
        AccountId accountId = verificationTokenPort.peek(rawToken);

        activationSteps.activate(accountId);

        // Only now, and only on the way out: everything above has committed.
        verificationTokenPort.invalidate(rawToken);

        log.info("Account activated via email verification: id={}", accountId);
    }
}
