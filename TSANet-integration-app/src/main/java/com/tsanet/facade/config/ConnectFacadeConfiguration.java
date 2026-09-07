package com.tsanet.facade.config;

import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConnectionSettings;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.ApplicationUserAccountRegistry;
import com.tsanet.api.session.AccountScopedTsaNetApiSession;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ConnectFacadeProperties.class, CliProperties.class, WebhookProperties.class})
public class ConnectFacadeConfiguration {
    @Bean
    TsaNetApiSessionFactory tsaNetApiSessionFactory(ConnectFacadeProperties properties) {
        requireHttps(properties.api());
        return TsaNetApi.sessionFactory(TsaNetApiConnectionSettings.of(
            properties.api().baseUrl(),
            properties.storage() != null ? properties.storage().sqlitePath() : "data.db"
        ));
    }

    /**
     * A bearer over plain http is a credential on the wire. Fail at startup, with the fix in
     * the message, rather than at the first request.
     */
    static void requireHttps(ConnectFacadeProperties.Api api) {
        String baseUrl = api == null ? null : api.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("tsanet.api.base-url is required");
        }
        if (baseUrl.regionMatches(true, 0, "https://", 0, 8)) {
            return;
        }
        if (api.insecureHttpAllowed()) {
            return;
        }
        throw new IllegalStateException("tsanet.api.base-url must use https; the Connect API is HTTPS-only in every real "
            + "environment and a bearer token must not travel in plain text. Set tsanet.api.allow-insecure-http=true "
            + "only for a local mock.");
    }

    @Bean
    ApplicationUserAccountRegistry applicationUserAccountRegistry(ConnectFacadeProperties properties) {
        return properties.toAccountRegistry();
    }

    @Bean
    TsaNetApiSession tsaNetApiSession(
        TsaNetApiSessionFactory sessionFactory,
        ApplicationUserAccountRegistry accountRegistry
    ) {
        return new AccountScopedTsaNetApiSession(sessionFactory, accountRegistry);
    }
}
