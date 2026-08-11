package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.generated.api.IdentityApi;
import com.tsanet.api.generated.model.LoginRequestDTO;
import com.tsanet.api.generated.model.TokenDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectApiAuthGatewayTest {
    @Mock
    private IdentityApi identityApi;

    @Test
    void itReturnsAccessTokenFromLoginResponse() {
        when(identityApi.login(any(LoginRequestDTO.class)))
            .thenReturn(new TokenDTO().accessToken("jwt-token-123"));

        ConnectApiAuthGateway gateway = new ConnectApiAuthGateway(identityApi);

        assertThat(gateway.login("api@test.com", "secret")).isEqualTo("jwt-token-123");

        ArgumentCaptor<LoginRequestDTO> captor = ArgumentCaptor.forClass(LoginRequestDTO.class);
        verify(identityApi).login(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("api@test.com");
        assertThat(captor.getValue().getPassword()).isEqualTo("secret");
    }

    @Test
    void itRejectsMissingAccessToken() {
        when(identityApi.login(any(LoginRequestDTO.class))).thenReturn(new TokenDTO());

        ConnectApiAuthGateway gateway = new ConnectApiAuthGateway(identityApi);

        assertThatThrownBy(() -> gateway.login("api@test.com", "secret"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("accessToken is missing");
    }
}
