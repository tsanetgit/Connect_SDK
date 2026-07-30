package com.tsanet.api.facade;

import java.util.Optional;

public interface AuthFacade {
    String login(String username, String password);

    String loginWithConfiguredCredentials();

    boolean isAuthorized();

    Optional<String> currentUsername();

    Optional<String> currentBearerToken();

    void logout();
}
