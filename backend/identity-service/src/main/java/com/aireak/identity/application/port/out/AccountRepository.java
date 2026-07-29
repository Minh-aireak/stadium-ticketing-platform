package com.aireak.identity.application.port.out;

import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.AccountRole;
import com.aireak.identity.domain.model.Email;

import java.util.Optional;

/**
 * Outbound port: Account persistence.
 * Implemented by AccountPersistenceAdapter in adapter/out/persistence.
 */
public interface AccountRepository {
    void save(Account account);
    Optional<Account> findById(AccountId accountId);
    Optional<Account> findByEmail(Email email);
    boolean existsByEmail(Email email);
    boolean existsByRole(AccountRole role);
}
