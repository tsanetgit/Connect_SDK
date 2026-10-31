package com.tsanet.demo.web;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.demo.config.CredentialsStore;
import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.EnvironmentService;
import com.tsanet.e2ee.E2eeProfile;
import java.util.function.Supplier;
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
        return session(environments.activeEnvironment());
    }

    /**
     * Authenticated session for environment {@code key}, built from that
     * environment's own stored credentials, for a caller that names the
     * environment (the Settings page, one card per environment). Every lookup is
     * keyed off {@code key}, so one environment's credentials never reach
     * another's API.
     */
    public TsaNetApiSession session(String key) {
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
            try {
                session.auth().authenticate();
            } catch (IllegalStateException refused) {
                // The reason the page shows for a failed sign-in, such as "OAuth client-credentials request
                // failed: HTTP 401". These messages name a status or a field, not a credential.
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, refused.getMessage());
            }
        }
        return session;
    }

    /**
     * Runs a facade call whose input the SDK validates, so that a value it refuses, such as a note over
     * its length limit, answers 400 with the SDK's own message instead of an unexpected failure. Resolve
     * the session before this, so that a server misconfiguration isn't reported as the caller's mistake.
     * Only the exact type: a subclass ({@link NumberFormatException}, Spring's {@code InvalidUrlException})
     * didn't come from a validator, and its message can quote a value.
     */
    public static <T> T sdkInput(Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException refused) {
            if (refused.getClass() != IllegalArgumentException.class) {
                throw refused;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refused.getMessage());
        }
    }

    /**
     * The E2EE profile paired with {@code session} (one of {@link #session()}'s), or null when
     * E2EE is off, or when the session was replaced by a credentials change since it was looked up
     * (tsanetgit/Connect_SDK#149). Resolved from the session, not from the active environment, for
     * the same reason {@link #session()} snapshots it once. A caller that was asked to encrypt and
     * gets null refuses; it never falls back to plaintext.
     */
    public E2eeProfile e2ee(TsaNetApiSession session) {
        return environments.e2eeFor(session);
    }

    /** Whether the demo holds its own E2EE keys; with them loaded, a null {@link #e2ee} means a replaced session. */
    public boolean e2eeEnabled() {
        return environments.e2eeKeys().enabled();
    }
}
