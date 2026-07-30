package com.tsanet.application.config;

import com.tsanet.api.ApplicationUserAccountRegistry;
import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConnectionSettings;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.session.AccountScopedTsaNetApiSession;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TsaNetApplicationProperties.class, TsaNetSyncProperties.class})
public class TsaNetApiConfigurationBean {
    @Bean
    TsaNetApiSessionFactory tsaNetApiSessionFactory(TsaNetApplicationProperties properties) {
        return TsaNetApi.sessionFactory(TsaNetApiConnectionSettings.of(
            properties.api().baseUrl(),
            properties.storage() != null ? properties.storage().sqlitePath() : "data.db"
        ));
    }

    @Bean
    ApplicationUserAccountRegistry applicationUserAccountRegistry(TsaNetApplicationProperties properties) {
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
