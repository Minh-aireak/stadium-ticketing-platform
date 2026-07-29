package com.aireak.identity.config;

import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.AccountRole;
import com.aireak.identity.domain.model.AccountStatus;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PasswordHashPort passwordHashPort;

    private AdminBootstrapRunner runner(String email, String password) {
        return new AdminBootstrapRunner(accountRepository, passwordHashPort,
                new AdminBootstrapProperties(email, password));
    }

    @Test
    void doesNothingWhenEnvVarsAreUnset() throws Exception {
        runner("", "").run(null);

        verifyNoInteractions(accountRepository, passwordHashPort);
    }

    @Test
    void doesNothingWhenOnlyEmailIsSet() throws Exception {
        runner("admin@example.com", "").run(null);

        verifyNoInteractions(accountRepository, passwordHashPort);
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() throws Exception {
        when(accountRepository.existsByRole(AccountRole.ADMIN)).thenReturn(true);

        runner("admin@example.com", "Bootstrap1").run(null);

        verify(accountRepository, never()).findByEmail(any());
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(passwordHashPort);
    }

    @Test
    void createsANewAdminAccountWhenNoAccountWithThatEmailExists() throws Exception {
        when(accountRepository.existsByRole(AccountRole.ADMIN)).thenReturn(false);
        when(accountRepository.findByEmail(new Email("admin@example.com"))).thenReturn(Optional.empty());
        when(passwordHashPort.hash(any(RawPassword.class))).thenReturn(new HashedPassword("$2a$12$hashed"));

        runner("admin@example.com", "Bootstrap1").run(null);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(AccountRole.ADMIN);
        assertThat(saved.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(saved.getValue().getEmail()).isEqualTo(new Email("admin@example.com"));
    }

    @Test
    void promotesAnExistingAccountWithThatEmailInsteadOfCreatingADuplicate() throws Exception {
        when(accountRepository.existsByRole(AccountRole.ADMIN)).thenReturn(false);
        Account existing = Account.register(new Email("admin@example.com"),
                new HashedPassword("$2a$12$existingHash"), "token");
        when(accountRepository.findByEmail(new Email("admin@example.com"))).thenReturn(Optional.of(existing));

        runner("admin@example.com", "Bootstrap1").run(null);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(AccountRole.ADMIN);
        assertThat(saved.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verifyNoInteractions(passwordHashPort);
    }
}
