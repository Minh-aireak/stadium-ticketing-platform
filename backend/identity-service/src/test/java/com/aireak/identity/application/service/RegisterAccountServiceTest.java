package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.command.RegisterAccountCommand;
import com.aireak.identity.application.port.out.AccountRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.exception.EmailAlreadyRegisteredException;
import com.aireak.identity.domain.model.Account;
import com.aireak.identity.domain.model.Email;
import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterAccountServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private PasswordHashPort passwordHashPort;
    @Mock
    private DomainEventPublisher eventPublisher;

    private RegisterAccountService newService() {
        return new RegisterAccountService(accountRepository, passwordHashPort, eventPublisher);
    }

    @Test
    void registersANewAccountHashesThePasswordAndPublishesTheEvent() {
        RegisterAccountService service = newService();
        Email email = new Email("new-user@example.com");
        HashedPassword hashed = new HashedPassword("$2a$12$hashedValue");
        when(accountRepository.existsByEmail(email)).thenReturn(false);
        when(passwordHashPort.hash(any(RawPassword.class))).thenReturn(hashed);

        String accountId = service.execute(new RegisterAccountCommand(email.value(), "Abcdefg1"));

        assertThat(accountId).isNotBlank();
        ArgumentCaptor<Account> savedAccount = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(savedAccount.capture());
        assertThat(savedAccount.getValue().getEmail()).isEqualTo(email);
        assertThat(savedAccount.getValue().getPassword()).isEqualTo(hashed);

        ArgumentCaptor<List<Object>> publishedEvents = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publishAll(publishedEvents.capture());
        assertThat(publishedEvents.getValue()).hasSize(1);
        assertThat(publishedEvents.getValue().get(0)).isInstanceOf(AccountRegisteredEvent.class);
    }

    @Test
    void rejectsRegistrationWhenEmailAlreadyExistsWithoutHashingOrSaving() {
        RegisterAccountService service = newService();
        Email email = new Email("existing@example.com");
        when(accountRepository.existsByEmail(email)).thenReturn(true);

        assertThatThrownBy(() -> service.execute(new RegisterAccountCommand(email.value(), "Abcdefg1")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(passwordHashPort, never()).hash(any());
        verify(accountRepository, never()).save(any());
        verify(eventPublisher, never()).publishAll(any());
    }

    // The fast-path existsByEmail() check above is a courtesy, not the authoritative guard —
    // AccountPersistenceAdapter.save() translates a unique-constraint DataIntegrityViolationException
    // (concurrent registration race) into this same exception. This documents that the service
    // layer has no special handling of its own: it just lets whatever accountRepository.save()
    // throws propagate, matching the adapter's contract.
    @Test
    void propagatesTheAdapterTranslatedExceptionOnAConcurrentRegistrationRace() {
        RegisterAccountService service = newService();
        Email email = new Email("racing@example.com");
        when(accountRepository.existsByEmail(email)).thenReturn(false);
        when(passwordHashPort.hash(any(RawPassword.class))).thenReturn(new HashedPassword("$2a$12$hash"));
        doThrow(new EmailAlreadyRegisteredException(email.value())).when(accountRepository).save(any());

        assertThatThrownBy(() -> service.execute(new RegisterAccountCommand(email.value(), "Abcdefg1")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(eventPublisher, never()).publishAll(any());
    }
}
