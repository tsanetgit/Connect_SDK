package com.tsanet.api.connectapi.internal;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Re-sends a request once with a renewed bearer after a 401, when the token manager can renew.
 * Concurrent 401s share one renewal: each passes the bearer it sent, and the manager renews
 * only if the store still holds that bearer.
 */
public final class OAuth401RetryInterceptor implements ClientHttpRequestInterceptor {
    private final TokenManager tokenManager;

    public OAuth401RetryInterceptor(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() != 401 || !tokenManager.supportsRefresh()) {
            return response;
        }
        response.close();
        String refreshedToken = tokenManager.renewUnlessAlreadyRenewed(bearerSent(request));
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedToken);
        return execution.execute(request, body);
    }

    /** The bearer this request carried, so a renewal another caller already made is reused. */
    private static String bearerSent(HttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            return null;
        }
        return authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7) : authorization;
    }
}
