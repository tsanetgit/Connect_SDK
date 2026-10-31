package com.tsanet.api.connectapi.internal;

import com.tsanet.api.generated.api.IdentityApi;
import com.tsanet.api.generated.model.LoginRequestDTO;
import com.tsanet.api.generated.model.TokenDTO;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.RestClientException;

public class ConnectApiAuthGateway {
    private final IdentityApi identityApi;

    public ConnectApiAuthGateway(IdentityApi identityApi) {
        this.identityApi = identityApi;
    }

    /**
     * Unauthenticated by nature: this gateway must sit on the runtime's login client, the one
     * without the bearer supplier and the 401 re-auth interceptor, or a re-login on an expired
     * token would recurse into itself.
     */
    public PasswordLogin login(String username, String password) {
        LoginRequestDTO request = new LoginRequestDTO().username(username).password(password);
        TokenDTO response;
        try {
            response = identityApi.login(request);
        } catch (RestClientException unreadable) {
            if (!unreadable.contains(HttpMessageNotReadableException.class)) {
                throw unreadable;
            }
            // No cause: Jackson's message quotes a token it doesn't recognize, which can be this one.
            throw new IllegalStateException("Failed to parse login response");
        } catch (IllegalArgumentException unreadable) {
            // The JDK client throws this on a base URL it won't accept and on a response it can't read,
            // quoting what it refused, so the message covers both.
            throw new IllegalStateException("The login request failed: its URL or the response isn't usable");
        }
        if (response == null || response.getAccessToken() == null || response.getAccessToken().isBlank()) {
            throw new IllegalStateException("Login succeeded but accessToken is missing");
        }
        return new PasswordLogin(BearerTokens.requireSendable(response.getAccessToken()), response.getExpiresIn());
    }
}
