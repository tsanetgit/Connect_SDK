package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.generated.api.CaseResponsesApi;
import com.tsanet.api.generated.api.IdentityApi;
import com.tsanet.api.generated.invoker.ApiClient;
import com.tsanet.api.generated.model.LoginRequestDTO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.RestTemplate;

/**
 * A 2xx whose body fails while being read used to escape the interceptor chain and surface as
 * Spring's {@code ResourceAccessException}, whose message embeds the request URL and with it the
 * case token. The connectivity interceptor now reads the (buffered) body inside its own try, so
 * the failure is classified like any other transport failure, and the extractor still receives
 * the same bytes afterwards.
 */
class ConnectApiConnectivityInterceptorBodyReadTest {

    private static final String BASE = "https://connect.example";
    private static final String TOKEN = "CASETOKEN-MARKER";

    @Test
    void aBodyThatFailsMidReadIsAConnectivityFailureNamingOnlyTheClass() {
        ApiClient apiClient = clientAnswering(response(HttpStatus.OK, MediaType.APPLICATION_JSON, new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (served++ < 3) {
                    return '{';
                }
                throw new IOException("Connection reset by peer while reading " + BASE + "/" + TOKEN);
            }
        }));

        assertThatThrownBy(() -> new CaseResponsesApi(apiClient).closeCollaborationRequest(TOKEN))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> {
                ConnectApiException ex = (ConnectApiException) e;
                assertThat(ex.kind()).isEqualTo(ConnectApiException.Kind.CONNECTIVITY);
                assertThat(ex.detail()).isEqualTo("IOException");
                assertThat(ex.getMessage()).doesNotContain("MARKER").doesNotContain("connect.example");
            });
    }

    @Test
    void theExtractorStillReceivesTheWholeBodyAfterTheInterceptorReadIt() {
        byte[] body = "{\"accessToken\":\"tok-1\",\"expiresIn\":3600}".getBytes(StandardCharsets.UTF_8);
        ApiClient apiClient = clientAnswering(response(HttpStatus.OK, MediaType.APPLICATION_JSON, new ByteArrayInputStream(body)));

        assertThat(new IdentityApi(apiClient).login(new LoginRequestDTO().username("u").password("p")).getAccessToken())
            .isEqualTo("tok-1");
    }

    @Test
    void anEmptyNoContentAnswerIsNotAFailure() {
        ApiClient apiClient = clientAnswering(response(HttpStatus.NO_CONTENT, null, InputStream.nullInputStream()));

        assertThat(new CaseResponsesApi(apiClient).closeCollaborationRequest(TOKEN)).isNull();
    }

    /** The library's own template, with production's buffering factory over a canned response. */
    private static ApiClient clientAnswering(ClientHttpResponse canned) {
        RestTemplate restTemplate = ConnectApiRestTemplates.create();
        ClientHttpRequestFactory answering = (URI uri, HttpMethod method) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            request.setResponse(canned);
            return request;
        };
        restTemplate.setRequestFactory(new BufferingClientHttpRequestFactory(answering));
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(BASE);
        apiClient.setBearerToken(() -> "bearer-1");
        return apiClient;
    }

    private static ClientHttpResponse response(HttpStatus status, MediaType contentType, InputStream body) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() {
                return status;
            }

            @Override
            public String getStatusText() {
                return status.getReasonPhrase();
            }

            @Override
            public void close() {
            }

            @Override
            public InputStream getBody() {
                return body;
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
