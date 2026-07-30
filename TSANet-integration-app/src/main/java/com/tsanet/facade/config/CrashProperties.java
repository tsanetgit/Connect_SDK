package com.tsanet.facade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "crash")
public record CrashProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("2000") int sshPort,
    @DefaultValue("crash") String authUsername,
    @DefaultValue("crash") String authPassword
) {
}
