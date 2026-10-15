package com.tsanet.api.connectapi.internal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The one way this library builds a {@link RestTemplate} for the Connect API: buffered
 * request bodies (the 401 re-auth retry re-sends the body), the response error handler that
 * yields {@code ConnectApiException}, the connectivity interceptor, and the {@code Accept}
 * header that asks for problem details. The runtime builds two: an unauthenticated one for
 * login, and the session one that additionally carries the bearer supplier and the 401 retry.
 * Tests build theirs here too, so what they exercise is what production runs.
 *
 * <p><b>Transport.</b> The JDK's {@link HttpClient}, not {@code HttpURLConnection}: Spring 7
 * streams every request body to {@code HttpURLConnection}, which drops the body of a 401 answer
 * to a streamed request, so a bad login lost its problem details (tsanetgit/Connect_SDK#128).
 * HTTP/1.1, no response compression and no timeouts, as before. Redirects are never followed;
 * the old transport followed them for a {@code GET}. The proxy selector and SSL context are the
 * JVM defaults, read once when the template is built. No {@code Authenticator} is set: with one,
 * the JDK client turns a 401 without {@code WWW-Authenticate}, which is how the API answers,
 * into an {@code IOException}, so the re-auth retry would never see the 401.
 */
public final class ConnectApiRestTemplates {

    private static final List<MediaType> JSON_ONLY = List.of(MediaType.APPLICATION_JSON);
    private static final List<MediaType> JSON_AND_PROBLEM =
        List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);

    private ConnectApiRestTemplates() {
    }

    public static RestTemplate create() {
        RestTemplate restTemplate = new RestTemplate(
            new BufferingClientHttpRequestFactory(jdkRequestFactory())
        );
        restTemplate.setErrorHandler(new ConnectApiResponseErrorHandler());
        restTemplate.getInterceptors().add(new ConnectApiConnectivityInterceptor());
        restTemplate.getInterceptors().add(ConnectApiRestTemplates::askForProblemDetails);
        return restTemplate;
    }

    private static JdkClientHttpRequestFactory jdkRequestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
        factory.enableCompression(false);
        return factory;
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
