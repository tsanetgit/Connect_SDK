package com.tsanet.api.connectapi.internal;

import com.tsanet.api.ConnectApiException;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Turns a transport failure into {@link ConnectApiException} of kind {@code CONNECTIVITY}
 * before it can leave the interceptor chain: once an {@link IOException} reaches
 * {@code RestTemplate} it becomes a {@code ResourceAccessException} whose message embeds the
 * request URL, and the URL carries the case token. Installed first, so it also wraps the
 * 401 re-auth retry.
 */
public final class ConnectApiConnectivityInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
        try {
            return execution.execute(request, body);
        } catch (IOException e) {
            throw ConnectApiException.connectivity(e);
        }
    }
}
