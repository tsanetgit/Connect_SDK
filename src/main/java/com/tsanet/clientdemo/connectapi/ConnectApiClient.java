package com.tsanet.clientdemo.connectapi;

import java.util.Optional;

public interface ConnectApiClient {
    String login(String username, String password);

    boolean isAuthorized();

    Optional<String> currentUsername();

    Optional<String> currentBearerToken();

    void logout();
}
