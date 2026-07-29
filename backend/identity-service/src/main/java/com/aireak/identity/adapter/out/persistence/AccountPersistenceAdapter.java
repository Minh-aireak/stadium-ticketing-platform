package com.aireak.identity.adapter.out.persistence;

import com.aireak.identity.domain.exception.EmailAlreadyRegisteredException;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.domain.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Persistence adapter implementing {@link AccountRepository} port.
 * Handles domain ↔ JPA entity mapping inline (simple enough not to need MapStruct here).
 */
@Component
@RequiredArgsConstructor
public class AccountPersistenceAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;

    /**
     * Updates load the existing managed entity first so {@code @Version} and
     * {@code createdAt} are preserved — building a fresh detached entity on every
     * save would null out the version and break optimistic locking on concurrent updates.
     */
    @Override
    public void save(Account account) {
        try {
            AccountJpaEntity entity = jpaRepository.findById(account.getId().value())
                    .map(existing -> applyDomainState(existing, account))
                    .orElseGet(() -> toNewJpaEntity(account));
            jpaRepository.save(entity);
        } catch (DataIntegrityViolationException ex) {
            // Unique constraint race: two concurrent registrations for the same email.
            throw new EmailAlreadyRegisteredException(account.getEmail().value());
        }
    }

    @Override
    public Optional<Account> findById(AccountId accountId) {
        return jpaRepository.findById(accountId.value()).map(this::toDomain);
    }

    @Override
    public Optional<Account> findByEmail(Email email) {
        return jpaRepository.findByEmail(email.value()).map(this::toDomain);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return jpaRepository.existsByEmail(email.value());
    }

    @Override
    public boolean existsByRole(AccountRole role) {
        return jpaRepository.existsByRole(role);
    }

    // ---- Mapping ----

    private AccountJpaEntity toNewJpaEntity(Account account) {
        return AccountJpaEntity.builder()
                .id(account.getId().value())
                .email(account.getEmail().value())
                .passwordHash(account.getPassword().value())
                .status(account.getStatus())
                .role(account.getRole())
                .registeredAt(account.getRegisteredAt())
                .build();
    }

    private AccountJpaEntity applyDomainState(AccountJpaEntity entity, Account account) {
        entity.setEmail(account.getEmail().value());
        entity.setPasswordHash(account.getPassword().value());
        entity.setStatus(account.getStatus());
        entity.setRole(account.getRole());
        return entity;
    }

    private Account toDomain(AccountJpaEntity entity) {
        return Account.reconstitute(
                new AccountId(entity.getId()),
                new Email(entity.getEmail()),
                new HashedPassword(entity.getPasswordHash()),
                entity.getStatus(),
                entity.getRegisteredAt(),
                entity.getRole()
        );
    }
}
