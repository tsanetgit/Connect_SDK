package com.tsanet.clientdemo.connectapi.internal;

import com.tsanet.clientdemo.config.ApiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ConnectApiAuthGateway {
    private final RestClient restClient;
    private final ApiProperties apiProperties;

    public ConnectApiAuthGateway(ApiProperties apiProperties, RestClient.Builder restClientBuilder) {
        this.apiProperties = apiProperties;
        this.restClient = restClientBuilder.baseUrl(apiProperties.baseUrl()).build();
    }

    public String login(String username, String password) {
        LoginResponse response = restClient.post()
            .uri(apiProperties.loginPath())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LoginRequest(username, password))
            .retrieve()
            .body(LoginResponse.class);

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("Login succeeded but accessToken is missing");
        }
        return response.accessToken();
    }

    private record LoginRequest(String username, String password) {
    }

    private record LoginResponse(String accessToken, String tokenType, Integer expiresIn) {
    }
}
