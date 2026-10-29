package com.tsanet.demo.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tsanet.demo")
public record DemoProperties(
    Map<String, EnvironmentDef> environments,
    String defaultEnvironment,
    String dataDir,
    Boolean allowInsecureHttp,
    Boolean receiverStorageEditable,
    Boolean credentialsLocked
) {

    /** The operator's opt-out of the https requirement, for a local mock; null means false. */
    public boolean insecureHttpAllowed() {
        return Boolean.TRUE.equals(allowInsecureHttp);
    }

    /**
     * Whether Settings may register a V2 receiver storage configuration; null means false. Off by
     * default because the platform lets any API user of a company repoint its storage
     * (tsanetgit/Connect-API-Code#170), and a hosted demo shouldn't make that one click away.
     * Reading and testing the configuration don't depend on it.
     */
    public boolean receiverStorageEditingAllowed() {
        return Boolean.TRUE.equals(receiverStorageEditable);
    }

    /**
     * Whether the stored member credentials may be changed through Settings; null means false.
     * On a hosted demo the operator pre-loads a credential per environment and turns this on, so
     * visitors only switch environments: the page shows who the demo is signed in as, and the
     * save and clear endpoints refuse (tsanetgit/Connect_SDK#152). Off by default because a local
     * run has nobody but the operator to enter credentials. Independent of the gate login: both
     * gate users see the same page (tsanetgit/Connect_SDK#148).
     */
    public boolean credentialChangesLocked() {
        return Boolean.TRUE.equals(credentialsLocked);
    }

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
