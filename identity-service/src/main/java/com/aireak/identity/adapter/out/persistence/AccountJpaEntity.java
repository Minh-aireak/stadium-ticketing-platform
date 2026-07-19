package com.aireak.identity.adapter.out.persistence;

import com.aireak.common.persistence.BaseAuditEntity;
import com.aireak.identity.domain.model.AccountStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for Account persistence.
 * Deliberately separate from the domain {@link com.aireak.identity.domain.model.Account} aggregate.
 * Mapping between domain ↔ entity is done in {@link AccountPersistenceAdapter}.
 */
@Getter
@Setter
@Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(columnNames = "email", name = "uq_accounts_email"))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountJpaEntity extends BaseAuditEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AccountStatus status;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Version
    @Column(name = "version")
    private Long version; // optimistic lock — defense layer 2 against concurrent writes
}
