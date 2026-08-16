package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.ResetPasswordUseCase;
import com.aireak.identity.application.port.in.command.ResetPasswordCommand;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.application.port.out.PasswordResetTokenPort;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.domain.exception.InvalidPasswordResetTokenException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.RawPassword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service: consumes a one-time password reset token and sets the new password.
 *
 * <p>The new password is validated against the current policy ({@link RawPassword}) <em>before</em>
 * the token is consumed, so a rejected password doesn't burn the customer's only reset link.
 *
 * <p>Every refresh session the account has is revoked once the new password is stored. A reset is
 * the customer's remedy for an account they believe is compromised, and it is worth nothing if the
 * intruder's existing session keeps refreshing itself past the change. Already-issued access
 * tokens still run to their own short expiry — they are stateless by design and nothing here can
 * recall them — but no new one can be minted after this point without the new password.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResetPasswordService implements ResetPasswordUseCase {

    private final PasswordResetTokenPort resetTokenPort;
    private final AccountRepository accountRepository;
    private final PasswordHashPort passwordHashPort;
    private final RefreshSessionStorePort refreshSessionStore;

    @Override
    @Transactional
    public void execute(ResetPasswordCommand command) {
        RawPassword newPassword = new RawPassword(command.newPassword());

        AccountId accountId = resetTokenPort.consume(command.token());
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new InvalidPasswordResetTokenException("Unknown account for reset token"));

        account.changePassword(passwordHashPort.hash(newPassword));
        accountRepository.save(account);
        refreshSessionStore.revokeAllForAccount(accountId);

        log.info("Password reset completed: id={}", accountId);
    }
}
