package com.tsanet.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.connectapi.internal.ConnectApiSessionStore;
import com.tsanet.api.connectapi.internal.TokenManager;
import org.junit.jupiter.api.Test;

/** The bearer supplier: absent token means no header; a renewable token is renewed; an unrenewable expiry is a classified 401. */
class TsaNetApiRuntimeBearerTest {

    @Test
    void noSessionMeansNoBearer() {
        assertThat(TsaNetApiRuntime.bearerTokenForRequest(new ConnectApiSessionStore(), mock(TokenManager.class),
            mock(TsaNetApiConfiguration.class))).isNull();
    }

    @Test
    void aRenewableSessionYieldsTheManagersToken() {
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        store.savePassword("user@test.com", "stale");
        TokenManager tokenManager = mock(TokenManager.class);
        when(tokenManager.ensureValidAccessToken()).thenReturn("fresh");

        assertThat(TsaNetApiRuntime.bearerTokenForRequest(store, tokenManager, mock(TsaNetApiConfiguration.class))).isEqualTo("fresh");
    }

    @Test
    void anExpiredSessionThatCannotBeRenewedIsAClassifiedFourOhOne() {
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        store.savePassword("typed@test.com", "stale");
        TokenManager tokenManager = mock(TokenManager.class);
        when(tokenManager.ensureValidAccessToken())
            .thenThrow(new IllegalStateException("Session expired and cannot be renewed with the configured credentials; log in again"));

        assertThatThrownBy(() -> TsaNetApiRuntime.bearerTokenForRequest(store, tokenManager, mock(TsaNetApiConfiguration.class)))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> {
                ConnectApiException ex = (ConnectApiException) e;
                assertThat(ex.status()).isEqualTo(401);
                assertThat(ex.title()).isEqualTo("Session expired");
                assertThat(ex.getMessage()).contains("log in again");
            });
    }
}
