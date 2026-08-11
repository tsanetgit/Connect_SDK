package com.tsanet.api.facade;

import com.tsanet.api.auth.AuthMode;
import java.time.Instant;
import java.util.Optional;

public interface AuthFacade {
    String authenticate();

    String login(String username, String password);

    String loginWithConfiguredCredentials();

    boolean isAuthorized();

    Optional<String> currentUsername();

    Optional<String> currentAccountId();

    Optional<AuthMode> authMode();

    Optional<Instant> tokenExpiresAt();

    Optional<String> currentBearerToken();

    void logout();
}
