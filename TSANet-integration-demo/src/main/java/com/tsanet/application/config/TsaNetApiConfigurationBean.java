package com.tsanet.application.config;

import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConnectionSettings;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
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
            properties.storage().sqlitePath()
        ));
    }

    @Bean
    TsaNetApiSession tsaNetApiSession(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetApplicationProperties properties
    ) {
        return sessionFactory.openSession(
            "default",
            properties.auth().username(),
            properties.auth().password()
        );
    }
}
