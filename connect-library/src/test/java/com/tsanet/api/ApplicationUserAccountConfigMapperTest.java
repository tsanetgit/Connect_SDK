package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
import org.junit.jupiter.api.Test;

class ApplicationUserAccountConfigMapperTest {
    @Test
    void itBuildsPasswordAccountFromLegacyFields() {
        ApplicationUserAccount account = ApplicationUserAccountConfigMapper.fromLegacyFields(
            "acme",
            "/tmp/acme.db",
            "api@test.com",
            "secret"
        );

        assertThat(account.id()).isEqualTo("acme");
        assertThat(account.sqlitePath()).isEqualTo("/tmp/acme.db");
        assertThat(account.auth()).isInstanceOf(PasswordAuthConfig.class);
        assertThat(account.auth().mode()).isEqualTo(AuthMode.CONNECT1_PASSWORD);
        assertThat(account.username()).contains("api@test.com");
    }

    @Test
    void itBuildsClientCredentialsAccountFromAuthType() {
        ApplicationUserAccount account = ApplicationUserAccountConfigMapper.fromAuthType(
            "production",
            "/tmp/prod.db",
            "client-credentials",
            null,
            null,
            "tenant",
            null,
            "client-id",
            "client-secret",
            "api://audience",
            null
        );

        assertThat(account.auth()).isInstanceOf(ClientCredentialsAuthConfig.class);
        assertThat(account.auth().mode()).isEqualTo(AuthMode.CLIENT_CREDENTIALS);
        ClientCredentialsAuthConfig oauth = (ClientCredentialsAuthConfig) account.auth();
        assertThat(oauth.resolvedTokenUrl()).contains("tenant");
        assertThat(oauth.resolvedScope()).isEqualTo("api://audience/.default");
    }
}
