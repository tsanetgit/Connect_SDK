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
}
