package com.tsanet.demo.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tsanet.demo")
public record DemoProperties(
    Map<String, EnvironmentDef> environments,
    String defaultEnvironment,
    String dataDir
) {

    /**
     * One Connect environment. The Entra tenant and audience are fixed per
     * environment (config, not user input), so an operator choosing OAuth mode
     * only ever enters the member's client id and secret.
     */
    public record EnvironmentDef(String label, String apiBaseUrl, String entraTenantId, String entraAudience) {

        /** True when this environment is configured for OAuth client-credentials logins. */
        public boolean oauthAvailable() {
            return entraTenantId != null && !entraTenantId.isBlank()
                && entraAudience != null && !entraAudience.isBlank();
        }
    }

    public EnvironmentDef require(String key) {
        EnvironmentDef def = environments != null ? environments.get(key) : null;
        if (def == null) {
            throw new IllegalArgumentException("Unknown environment: " + key);
        }
        return def;
    }
}
