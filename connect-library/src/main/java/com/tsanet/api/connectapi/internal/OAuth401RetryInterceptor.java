package com.tsanet.api.connectapi.internal;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

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
        String refreshedToken = tokenManager.refreshAccessToken();
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedToken);
        return execution.execute(request, body);
    }
}
