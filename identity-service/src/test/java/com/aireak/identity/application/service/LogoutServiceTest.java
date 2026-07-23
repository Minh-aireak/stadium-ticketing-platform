package com.aireak.identity.application.service;

import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

    @Mock
    private RefreshSessionStorePort refreshSessionStorePort;

    private LogoutService service() {
        return new LogoutService(refreshSessionStorePort);
    }

    @Test
    void revokesTheSessionFamilyForAPresentToken() {
        service().execute("some-raw-token");

        verify(refreshSessionStorePort).revokeFamily("some-raw-token");
    }

    @Test
    void isANoOpForANullTokenInsteadOfErroring() {
        service().execute(null);

        verifyNoInteractions(refreshSessionStorePort);
    }

    @Test
    void isANoOpForABlankTokenInsteadOfErroring() {
        service().execute("   ");

        verify(refreshSessionStorePort, never()).revokeFamily(org.mockito.ArgumentMatchers.anyString());
    }
}
