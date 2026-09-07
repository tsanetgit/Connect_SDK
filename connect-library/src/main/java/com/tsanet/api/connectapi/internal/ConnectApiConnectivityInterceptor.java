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
 *
 * <p>The response body is read here too. The library's request factory buffers responses, so
 * this read fills the buffer the extractor and the error handler later read from; without it a
 * body that fails mid-read would fail after the chain has returned, in the extractor, and
 * surface with the URL in the same way.
 */
public final class ConnectApiConnectivityInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
        try {
            ClientHttpResponse response = execution.execute(request, body);
            response.getBody();
            return response;
        } catch (IOException e) {
            throw ConnectApiException.connectivity(e);
        }
    }
}
