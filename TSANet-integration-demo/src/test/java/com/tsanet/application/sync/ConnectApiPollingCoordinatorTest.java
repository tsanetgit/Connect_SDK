package com.tsanet.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.facade.AuthFacade;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectApiPollingCoordinatorTest {
    @Test
    void itClosesTheSessionItOpensForAPollEvenWhenThePollFails() {
        TsaNetApiSessionFactory factory = mock(TsaNetApiSessionFactory.class);
        TsaNetApiSession session = mock(TsaNetApiSession.class);
        AuthFacade auth = mock(AuthFacade.class);
        when(factory.openSessionForApplicationUser(any())).thenReturn(session);
        when(session.auth()).thenReturn(auth);
        when(auth.authenticate()).thenThrow(new IllegalStateException("refused"));
        ApplicationUserAccountRegistry registry = ApplicationUserAccountRegistry.of(
            List.of(ApplicationUserAccount.passwordAccount("alpha", "alpha.db", "alpha@test.com", "secret")));
        ConnectApiPollingCoordinator coordinator = new ConnectApiPollingCoordinator(factory, registry);

        coordinator.pollConfiguredAccounts();

        verify(session).close();
        assertThat(coordinator.lastSnapshots()).containsOnlyKeys("alpha");
    }
}
