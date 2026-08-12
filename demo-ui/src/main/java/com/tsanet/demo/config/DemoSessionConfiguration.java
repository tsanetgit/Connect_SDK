package com.tsanet.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DemoProperties.class)
public class DemoSessionConfiguration {
    // Sessions are created per environment by EnvironmentService; nothing to
    // wire eagerly here beyond enabling the properties binding.
}
