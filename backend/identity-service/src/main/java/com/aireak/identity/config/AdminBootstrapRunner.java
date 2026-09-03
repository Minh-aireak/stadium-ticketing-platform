package com.aireak.identity.config;

import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountRole;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.RawPassword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * One-time admin provisioning on startup: if {@code ADMIN_BOOTSTRAP_EMAIL} /
 * {@code ADMIN_BOOTSTRAP_PASSWORD} are set and no ADMIN account exists yet, creates (or promotes
 * an existing account with that email to) the platform's first ADMIN, active immediately.
 *
 * <p>Deliberately not a Flyway seed insert — a checked-in credential would be a permanent secret
 * leak (every clone of the repo/migration history has it forever); this only ever runs from an
 * operator-supplied env var, and is a no-op on every subsequent boot once an ADMIN exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AccountRepository accountRepository;
    private final PasswordHashPort passwordHashPort;
    private final AdminBootstrapProperties properties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.email().isBlank() || properties.password().isBlank()) {
            return;
        }
        if (accountRepository.existsByRole(AccountRole.ADMIN)) {
            return;
        }

        Email email = new Email(properties.email());
        // Hashed once, up front, because both branches need it: a promoted account takes the
        // operator's password exactly as a created one does. See Account#promoteToAdmin for why
        // an existing account's own password must not survive the promotion.
        var hashedPassword = passwordHashPort.hash(new RawPassword(properties.password()));

        Optional<Account> existing = accountRepository.findByEmail(email);
        if (existing.isPresent()) {
            Account account = existing.get();
            account.promoteToAdmin(hashedPassword);
            accountRepository.save(account);
            log.info("Admin bootstrap: promoted existing account to ADMIN and reset its password to the "
                    + "operator-supplied one, accountId={}", account.getId());
        } else {
            Account account = Account.registerAdmin(email, hashedPassword);
            accountRepository.save(account);
            log.info("Admin bootstrap: created new ADMIN account, accountId={}", account.getId());
        }
    }
}
