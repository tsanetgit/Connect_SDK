package com.tsanet.api.connectapi.internal;

import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The one way this library builds a {@link RestTemplate} for the Connect API: buffered
 * request bodies (the 401 re-auth retry re-sends the body), the response error handler that
 * yields {@code ConnectApiException}, and the connectivity interceptor. The runtime builds
 * two: an unauthenticated one for login, and the session one that additionally carries the
 * bearer supplier and the 401 retry. Tests build theirs here too, so what they exercise is
 * what production runs.
 */
public final class ConnectApiRestTemplates {

    private ConnectApiRestTemplates() {
    }

    public static RestTemplate create() {
        RestTemplate restTemplate = new RestTemplate(
            new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
        );
        restTemplate.setErrorHandler(new ConnectApiResponseErrorHandler());
        restTemplate.getInterceptors().add(new ConnectApiConnectivityInterceptor());
        return restTemplate;
    }
}
