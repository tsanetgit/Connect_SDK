package com.tsanet.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TsaNetScenarioProperties.class)
public class ScenarioConfiguration {
}
