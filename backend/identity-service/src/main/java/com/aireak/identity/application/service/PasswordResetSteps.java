package com.aireak.identity.application.service;

import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.domain.exception.InvalidPasswordResetTokenException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.RawPassword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of a password reset: the account read and the new hash, and nothing else.
 *
 * <p>Its own bean for the same proxy reason as {@link AccountActivationSteps}, and to keep two
 * Redis calls out of the database transaction — revoking the account's refresh sessions and
 * spending the reset token both used to run inside it. On Redisson's defaults a single Redis
 * command could take upwards of fifteen seconds, and every one of those seconds was a Hikari
 * connection held open for a round trip that the transaction cannot roll back anyway. That is the
 * shape notification-service already removed from its own send path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PasswordResetSteps {

    private final AccountRepository accountRepository;
    private final PasswordHashPort passwordHashPort;

    @Transactional
    public void changePassword(AccountId accountId, RawPassword newPassword) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new InvalidPasswordResetTokenException("Unknown account for reset token"));

        account.changePassword(passwordHashPort.hash(newPassword));
        accountRepository.save(account);
    }
}
