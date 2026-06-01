package com.tsanet.clientdemo.connectapi;

import com.tsanet.clientdemo.connectapi.internal.ConnectApiAuthGateway;
import com.tsanet.clientdemo.connectapi.internal.ConnectApiSessionStore;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DefaultConnectApiClient implements ConnectApiClient {
    private final ConnectApiAuthGateway authGateway;
    private final ConnectApiSessionStore sessionStore;

    public DefaultConnectApiClient(ConnectApiAuthGateway authGateway, ConnectApiSessionStore sessionStore) {
        this.authGateway = authGateway;
        this.sessionStore = sessionStore;
    }

    @Override
    public String login(String username, String password) {
        String token = authGateway.login(username, password);
        sessionStore.save(username, token);
        return token;
    }

    @Override
    public boolean isAuthorized() {
        return sessionStore.isAuthorized();
    }

    @Override
    public Optional<String> currentUsername() {
        return sessionStore.getUsername();
    }

    @Override
    public Optional<String> currentBearerToken() {
        return sessionStore.getBearerToken();
    }

    @Override
    public void logout() {
        sessionStore.clear();
    }
}
