package com.tsanet.facade.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

public class WebhookWebApplicationTypeEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean webhookEnabled = environment.getProperty("tsanet.webhook.enabled", Boolean.class, false);
        if (webhookEnabled) {
            application.setWebApplicationType(WebApplicationType.SERVLET);
        }
    }
}
