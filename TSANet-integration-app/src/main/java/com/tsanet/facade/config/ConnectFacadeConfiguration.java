package com.tsanet.facade.config;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ConnectApiBaseUrl;
import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConnectionSettings;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.ApplicationUserAccountRegistry;
import com.tsanet.api.session.AccountScopedTsaNetApiSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ConnectFacadeProperties.class, CliProperties.class, WebhookProperties.class})
public class ConnectFacadeConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectFacadeConfiguration.class);

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
     * the message, rather than at the first request. The decision itself lives in the library
     * ({@link ConnectApiBaseUrl}) so the console and both demos make it the same way.
     */
    static void requireHttps(ConnectFacadeProperties.Api api) {
        ConnectApiBaseUrl.requireHttps(
            api == null ? null : api.baseUrl(),
            api != null && api.insecureHttpAllowed(),
            "tsanet.api.base-url",
            "tsanet.api.allow-insecure-http"
        );
    }

    @Bean
    ApplicationUserAccountRegistry applicationUserAccountRegistry(ConnectFacadeProperties properties) {
        ApplicationUserAccountRegistry registry = properties.toAccountRegistry();
        logUnrestrictedAccounts(registry);
        return registry;
    }

    /**
     * An absent or misspelled {@code allowed-receiver-company-ids} binds as no list, which is
     * unrestricted, so say so at startup for each account that runs without one.
     */
    static void logUnrestrictedAccounts(ApplicationUserAccountRegistry registry) {
        for (ApplicationUserAccount account : registry.all()) {
            if (account.allowedReceiverCompanyIds().isEmpty()) {
                LOG.info("Account {} has no V2 receiver allowlist (allowed-receiver-company-ids): the SDK won't"
                    + " restrict which receivers it delivers attachments to; only the platform's own allowlist applies",
                    account.id());
            }
        }
    }

    @Bean
    TsaNetApiSession tsaNetApiSession(
        TsaNetApiSessionFactory sessionFactory,
        ApplicationUserAccountRegistry accountRegistry
    ) {
        return new AccountScopedTsaNetApiSession(sessionFactory, accountRegistry);
    }
}
