package com.tsanet.api.connectapi.internal;

import com.tsanet.api.generated.api.IdentityApi;
import com.tsanet.api.generated.model.LoginRequestDTO;
import com.tsanet.api.generated.model.TokenDTO;

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
        TokenDTO response = identityApi.login(request);
        if (response == null || response.getAccessToken() == null || response.getAccessToken().isBlank()) {
            throw new IllegalStateException("Login succeeded but accessToken is missing");
        }
        return new PasswordLogin(response.getAccessToken(), response.getExpiresIn());
    }
}
