package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * The 401 retry, now in front of both auth modes: when the token manager can renew, the
 * request is re-sent once with the new bearer; when it cannot (an interactively typed
 * password), the 401 passes through to the error handler, which is the "clear error" half.
 */
class OAuth401RetryInterceptorTest {

    @Test
    void aFourOhOneIsRetriedOnceWithTheRenewedBearerWhenRenewalIsPossible() throws IOException {
        TokenManager tokenManager = mock(TokenManager.class);
        when(tokenManager.supportsRefresh()).thenReturn(true);
        when(tokenManager.renewUnlessAlreadyRenewed("stale")).thenReturn("fresh");
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any()))
            .thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.UNAUTHORIZED))
            .thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://connect.example/v1/me"));
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer stale");

        ClientHttpResponse response = new OAuth401RetryInterceptor(tokenManager).intercept(request, new byte[0], execution);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer fresh");
        verify(execution, times(2)).execute(any(), any());
        verify(tokenManager).renewUnlessAlreadyRenewed("stale");
    }

    @Test
    void theBearerItSentIsPassedWithoutThePrefixSoARenewalAlreadyMadeIsReused() throws IOException {
        TokenManager tokenManager = mock(TokenManager.class);
        when(tokenManager.supportsRefresh()).thenReturn(true);
        when(tokenManager.renewUnlessAlreadyRenewed("stale")).thenReturn("already-fresh");
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any()))
            .thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.UNAUTHORIZED))
            .thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://connect.example/v1/me"));
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "bearer stale");

        new OAuth401RetryInterceptor(tokenManager).intercept(request, new byte[0], execution);

        assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer already-fresh");
        verify(tokenManager, times(1)).renewUnlessAlreadyRenewed("stale");
    }

    @Test
    void aFourOhOnePassesThroughWhenRenewalIsNotPossible() throws IOException {
        TokenManager tokenManager = mock(TokenManager.class);
        when(tokenManager.supportsRefresh()).thenReturn(false);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.UNAUTHORIZED));
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://connect.example/v1/me"));

        ClientHttpResponse response = new OAuth401RetryInterceptor(tokenManager).intercept(request, new byte[0], execution);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(execution, times(1)).execute(any(), any());
        verify(tokenManager, never()).renewUnlessAlreadyRenewed(any());
    }

    @Test
    void aNonFourOhOneIsNeverRetried() throws IOException {
        TokenManager tokenManager = mock(TokenManager.class);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.FORBIDDEN));
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://connect.example/v1/me"));

        ClientHttpResponse response = new OAuth401RetryInterceptor(tokenManager).intercept(request, new byte[0], execution);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(tokenManager, never()).renewUnlessAlreadyRenewed(any());
    }
}
