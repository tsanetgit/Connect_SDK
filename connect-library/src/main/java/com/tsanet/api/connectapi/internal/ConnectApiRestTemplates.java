package com.tsanet.api.connectapi.internal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The one way this library builds a {@link RestTemplate} for the Connect API: buffered
 * request bodies (the 401 re-auth retry re-sends the body), the response error handler that
 * yields {@code ConnectApiException}, the connectivity interceptor, and the {@code Accept}
 * header that asks for problem details. The runtime builds two: an unauthenticated one for
 * login, and the session one that additionally carries the bearer supplier and the 401 retry.
 * Both share one {@link HttpClient} per session, and the Entra token call uses the same client
 * through {@link #transport(HttpClient)}, without the Connect API's error handling
 * (tsanetgit/Connect_SDK#131). Tests build theirs here too, so what they exercise is what
 * production runs.
 *
 * <p><b>Transport.</b> The JDK's {@link HttpClient}, not {@code HttpURLConnection}: Spring 7
 * streams every request body to {@code HttpURLConnection}, which drops the body of a 401 answer
 * to a streamed request, so a bad login lost its problem details (tsanetgit/Connect_SDK#128).
 * HTTP/1.1, no response compression and no timeouts, as before. Redirects are never followed;
 * the old transport followed them for a {@code GET}. The proxy selector and SSL context are the
 * JVM defaults, read once when {@link #newHttpClient()} builds the client. No
 * {@code Authenticator} is set: with one, the JDK client turns a 401 without
 * {@code WWW-Authenticate}, which is how the API answers, into an {@code IOException}, so the
 * re-auth retry would never see the 401.
 */
public final class ConnectApiRestTemplates {

    private static final List<MediaType> JSON_ONLY = List.of(MediaType.APPLICATION_JSON);
    private static final List<MediaType> JSON_AND_PROBLEM =
        List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);

    private ConnectApiRestTemplates() {
    }

    public static RestTemplate create() {
        return create(newHttpClient());
    }

    public static RestTemplate create(HttpClient httpClient) {
        RestTemplate restTemplate = new RestTemplate(transport(httpClient));
        restTemplate.setErrorHandler(new ConnectApiResponseErrorHandler());
        restTemplate.getInterceptors().add(new ConnectApiConnectivityInterceptor());
        restTemplate.getInterceptors().add(ConnectApiRestTemplates::askForProblemDetails);
        return restTemplate;
    }

    /** The JDK client every Connect API template and the token call ride, as the class javadoc describes. */
    public static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /** The buffered request factory over {@code httpClient}, with no error handler or interceptors. */
    public static ClientHttpRequestFactory transport(HttpClient httpClient) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.enableCompression(false);
        return new BufferingClientHttpRequestFactory(factory);
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
