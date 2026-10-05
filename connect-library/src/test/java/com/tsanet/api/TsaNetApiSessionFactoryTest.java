package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TsaNetApiSessionFactoryTest {
    @Test
    void itDerivesDistinctSqlitePathsPerSessionLabel() {
        TsaNetApiSessionFactory factory = TsaNetApi.sessionFactory(
            TsaNetApiConnectionSettings.of("http://localhost:8080", "/tmp/demo/data.db")
        );

        assertThat(factory.sqlitePathFor("acme")).isEqualTo("/tmp/demo/data-acme.db");
        assertThat(factory.sqlitePathFor("beta")).isEqualTo("/tmp/demo/data-beta.db");
        assertThat(factory.sqlitePathForAccount("api@appko.com")).isEqualTo("/tmp/demo/data-api-appko.com.db");
    }

    @Test
    void itAppendsLabelWhenBasePathHasNoDbSuffix() {
        TsaNetApiSessionFactory factory = TsaNetApi.sessionFactory(
            TsaNetApiConnectionSettings.of("http://localhost:8080", "/tmp/cache")
        );

        assertThat(factory.sqlitePathFor("acme")).isEqualTo("/tmp/cache-acme");
    }

    @Test
    void anAccountsReceiverAllowlistReachesItsSessionConfiguration() {
        TsaNetApiSessionFactory factory = TsaNetApi.sessionFactory(
            TsaNetApiConnectionSettings.of("http://localhost:8080", "/tmp/cache")
        );
        ApplicationUserAccount account = ApplicationUserAccount.passwordAccount("acme", "/tmp/acme.db", "u", "p")
            .withAllowedReceiverCompanyIds(java.util.List.of(101L, 202L));

        assertThat(factory.configurationFor(account).allowedReceiverCompanyIds()).containsExactlyInAnyOrder(101L, 202L);
        assertThat(factory.configurationFor(ApplicationUserAccount.passwordAccount("beta", "/tmp/beta.db", "u", "p"))
            .allowedReceiverCompanyIds()).isEmpty();
    }

    @Test
    void anAbsentReceiverAllowlistIsEmptyMeaningUnrestricted() {
        var auth = new com.tsanet.api.auth.PasswordAuthConfig("u", "p");

        assertThat(new TsaNetApiConfiguration("http://x", "/tmp/x.db", "a", auth, null).allowedReceiverCompanyIds()).isEmpty();
        assertThat(new TsaNetApiConfiguration("http://x", "/tmp/x.db", "a", auth).allowedReceiverCompanyIds()).isEmpty();
        assertThat(new ApplicationUserAccount("a", "/tmp/x.db", auth, null).allowedReceiverCompanyIds()).isEmpty();
        assertThat(ApplicationUserAccount.passwordAccount("a", "/tmp/x.db", "u", "p").withAllowedReceiverCompanyIds(null)
            .allowedReceiverCompanyIds()).isEmpty();
    }

    @Test
    void aNullCompanyIdIsRefusedClearlyAndAnImmutableSetIsAccepted() {
        var auth = new com.tsanet.api.auth.PasswordAuthConfig("u", "p");
        java.util.List<Long> withNull = java.util.Arrays.asList(101L, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApplicationUserAccount.passwordAccount("a", "/tmp/x.db", "u", "p")
                .withAllowedReceiverCompanyIds(withNull))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TsaNetApiConfiguration("http://x", "/tmp/x.db", "a", auth,
                new java.util.HashSet<>(withNull)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
        assertThat(new TsaNetApiConfiguration("http://x", "/tmp/x.db", "a", auth, java.util.Set.of(101L))
            .allowedReceiverCompanyIds()).containsExactly(101L);
        assertThat(new ApplicationUserAccount("a", "/tmp/x.db", auth, java.util.Set.of(101L))
            .allowedReceiverCompanyIds()).containsExactly(101L);
    }
}
