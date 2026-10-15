package com.tsanet.api.connectapi.internal;

import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The one way this library builds a {@link RestTemplate} for the Connect API: buffered
 * request bodies (the 401 re-auth retry re-sends the body), the response error handler that
 * yields {@code ConnectApiException}, the connectivity interceptor, and the {@code Accept}
 * header that asks for problem details. The runtime builds two: an unauthenticated one for
 * login, and the session one that additionally carries the bearer supplier and the 401 retry.
 * Tests build theirs here too, so what they exercise is what production runs.
 */
public final class ConnectApiRestTemplates {

    private static final List<MediaType> JSON_ONLY = List.of(MediaType.APPLICATION_JSON);
    private static final List<MediaType> JSON_AND_PROBLEM =
        List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);

    private ConnectApiRestTemplates() {
    }

    public static RestTemplate create() {
        RestTemplate restTemplate = new RestTemplate(
            new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
        );
        restTemplate.setErrorHandler(new ConnectApiResponseErrorHandler());
        restTemplate.getInterceptors().add(new ConnectApiConnectivityInterceptor());
        restTemplate.getInterceptors().add(ConnectApiRestTemplates::askForProblemDetails);
        return restTemplate;
    }

    /**
     * The API answers a {@code /v1/} failure with RFC 7807 problem details only when the request's
     * {@code Accept} names {@code application/problem+json}; otherwise it sends its legacy
     * {@code {"message"}} body (tsanetgit/Connect_SDK#127). The generated client lists both types
     * but sends only {@code application/json}, and the generated source is rebuilt on every build,
     * so the header is widened here. Both types, not problem+json alone: alone, successful answers
     * come back typed {@code application/problem+json} too. Any other {@code Accept} is left as set.
     */
    private static ClientHttpResponse askForProblemDetails(HttpRequest request, byte[] body,
        ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();
        if (JSON_ONLY.equals(headers.getAccept())) {
            headers.setAccept(JSON_AND_PROBLEM);
        }
        return execution.execute(request, body);
    }
}
