package com.tsanet.clientdemo.connectapi.internal;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ConnectApiSessionStore {
    private volatile String bearerToken;
    private volatile String username;

    public void save(String username, String bearerToken) {
        this.username = username;
        this.bearerToken = bearerToken;
    }

    public Optional<String> getBearerToken() {
        return Optional.ofNullable(bearerToken);
    }

    public Optional<String> getUsername() {
        return Optional.ofNullable(username);
    }

    public boolean isAuthorized() {
        return bearerToken != null && !bearerToken.isBlank();
    }

    public void clear() {
        this.username = null;
        this.bearerToken = null;
    }
}
