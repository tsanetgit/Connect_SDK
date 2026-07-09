package com.tsanet.facade.config;

import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConnectionSettings;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.facade.session.AccountScopedTsaNetApiSession;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ConnectFacadeProperties.class, CliProperties.class, WebhookProperties.class})
public class ConnectFacadeConfiguration {
    @Bean
    TsaNetApiSessionFactory tsaNetApiSessionFactory(ConnectFacadeProperties properties) {
        return TsaNetApi.sessionFactory(TsaNetApiConnectionSettings.of(
            properties.api().baseUrl(),
            properties.storage().sqlitePath()
        ));
    }

    @Bean
    TsaNetApiSession tsaNetApiSession(
        TsaNetApiSessionFactory sessionFactory,
        ConnectFacadeProperties properties
    ) {
        Optional<AccountScopedTsaNetApiSession.ConfiguredCredentials> configuredCredentials =
            properties.auth().isConfigured()
                ? Optional.of(new AccountScopedTsaNetApiSession.ConfiguredCredentials(
                    properties.auth().username(),
                    properties.auth().password()
                ))
                : Optional.empty();
        return new AccountScopedTsaNetApiSession(sessionFactory, configuredCredentials);
    }
}
