package com.tsanet.application.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(TsaNetSyncProperties.class)
@ConditionalOnProperty(prefix = "tsanet.sync", name = "enabled", havingValue = "true")
public class PollingConfiguration {
}
