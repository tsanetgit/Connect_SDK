package com.tsanet.demo.web;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.demo.config.CredentialsStore;
import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.EnvironmentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the active environment's SDK session and ensures it is authenticated
 * before facade calls, using that environment's persisted credentials.
 */
@Component
public class SessionGuard {

    private final EnvironmentService environments;

    public SessionGuard(EnvironmentService environments) {
        this.environments = environments;
    }

    /**
     * Authenticated session for the active environment.
     *
     * <p>Credentials are loaded first because the SDK needs them to build the
     * session at all; there is no unauthenticated session to log into later.
     *
     * <p>The active environment is snapshotted once and every lookup below is
     * keyed off that snapshot. Reading it per call would let a concurrent
     * environment switch pair one environment's credentials with another's
     * session, sending the first environment's secret to the second's API.
     */
    public TsaNetApiSession session() {
        String key = environments.activeEnvironment();
        DemoProperties.EnvironmentDef def = environments.definitionFor(key);
        CredentialsStore.Credentials credentials = environments.credentialsFor(key).load()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.PRECONDITION_REQUIRED,
                def.label() + " credentials not configured - set them under Settings first"
            ));
        // Validate the credentials on their own, rather than wrapping the whole
        // of sessionFor. Session construction also rejects a blank apiBaseUrl or
        // sqlitePath with the same exception type, and reporting a server
        // misconfiguration as "set your credentials under Settings" would send
        // the operator to the wrong screen. Those surface as 500 instead.
        try {
            EnvironmentService.toAuthConfig(def, credentials);
        } catch (IllegalArgumentException e) {
            // Stored credentials no longer form a usable config for this
            // environment (for example OAuth creds kept after the Entra tenant
            // was removed from config). Same remedy as missing credentials.
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, e.getMessage());
        }
        TsaNetApiSession session = environments.sessionFor(key, credentials);
        if (!session.auth().isAuthorized()) {
            session.auth().authenticate();
        }
        return session;
    }
}
